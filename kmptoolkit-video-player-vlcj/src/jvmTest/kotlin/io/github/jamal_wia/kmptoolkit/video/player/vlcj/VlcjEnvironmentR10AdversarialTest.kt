package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The child of [VlcjEnvironmentR10AdversarialTest]: one scenario per run, in a JVM of its own so that a low
 * file-descriptor limit, a missing or read-only temp directory and a JVM shutdown with live players cannot touch the
 * Gradle test worker. Prints R10-OK and exits 0 when the scenario's expectations hold, anything else is a failure.
 */
@OptIn(ToolkitInternalApi::class)
internal object VlcjEnvironmentR10Child {

    private fun jarWithAsset(dir: File): File {
        val jar = File(dir, "assets.jar")
        JarOutputStream(jar.outputStream()).use { o ->
            o.putNextEntry(JarEntry("media/clip.mp4"))
            o.write(ByteArray(4_096) { it.toByte() })
            o.closeEntry()
        }
        return jar
    }

    private fun newPlayer(runtime: FakeVlcRuntime, loader: ClassLoader): VideoPlayer {
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            classLoader = loader,
            createRuntime = { runtime },
            teardown = java.util.concurrent.Executor { it.run() },
        )
        return createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L))
    }

    private fun prepare(player: VideoPlayer, source: VideoSource): VideoPlayerState = runBlocking {
        withTimeout(20_000L) { player.prepare(source) }
        player.stateFlow.value
    }

    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) {
            System.err.println("R10-FAIL: ${message()}")
            System.exit(3)
        }
    }

    private fun tempLeftovers(): List<String> =
        File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("kmptoolkit-video-") }?.toList().orEmpty()

    @JvmStatic
    fun main(args: Array<String>) {
        val mode: String = args[0]
        val scratch = File(args[1])
        val jar: File = jarWithAsset(scratch)
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
        val runtime = FakeVlcRuntime()
        when (mode) {
            "missing-tmpdir", "readonly-tmpdir" -> {
                val player: VideoPlayer = newPlayer(runtime, loader)
                val state: VideoPlayerState = prepare(player, VideoSource.Asset("media/clip.mp4"))
                expect(state is VideoPlayerState.Error) { "a jar asset with an unusable temp dir must end in Error, was $state" }
                expect(AssetCopies.liveCount == 0) { "copies registered after the failure: ${AssetCopies.liveCount}" }
                // A plain file needs no temp copy: it still plays on the same engine.
                val file: File = File(scratch, "plain.mp4").apply { writeBytes(ByteArray(10)) }
                val second: VideoPlayerState = prepare(player, VideoSource.File(file.path))
                expect(second is VideoPlayerState.Ready) { "a file source after the failure: $second" }
                player.release()
            }
            "fds" -> {
                // Warm-up: every class of the success and failure paths is loaded while descriptors are plentiful.
                run {
                    val warm: VideoPlayer = newPlayer(FakeVlcRuntime(), loader)
                    prepare(warm, VideoSource.Asset("media/missing.mp4"))
                    prepare(warm, VideoSource.File(File(scratch, "missing.mp4").path))
                    prepare(warm, VideoSource.Asset("media/clip.mp4"))
                    warm.play()
                    warm.pause()
                    warm.release()
                    Class.forName("java.nio.file.FileSystemException")
                    Class.forName("java.nio.file.NoSuchFileException")
                    Class.forName("java.io.FileNotFoundException")
                }
                val player: VideoPlayer = newPlayer(runtime, loader)
                // Exhaust the descriptors this process may still open.
                val hogs: MutableList<java.io.Closeable> = mutableListOf()
                try {
                    while (true) hogs += java.io.FileInputStream("/dev/null")
                } catch (_: java.io.IOException) {
                }
                val state: VideoPlayerState = prepare(player, VideoSource.Asset("media/clip.mp4"))
                expect(state is VideoPlayerState.Error) { "with no descriptor left a jar asset must end in Error, was $state (hogs=${hogs.size})" }
                expect(AssetCopies.liveCount == 0) { "copies registered after the failure: ${AssetCopies.liveCount}" }
                repeat(60) { hogs.removeLastOrNull()?.close() }
                val again: VideoPlayerState = prepare(player, VideoSource.Asset("media/clip.mp4"))
                expect(again is VideoPlayerState.Ready) { "after descriptors came back the next prepare must work, was $again" }
                player.release()
                expect(AssetCopies.liveCount == 0) { "copies registered after the release: ${AssetCopies.liveCount}" }
                hogs.forEach { it.close() }
            }
            "shutdown" -> {
                // Many players created, played and released on different threads while the JVM is shutting down;
                // copies of jar assets that are never discarded must be swept by the hook.
                val threads: List<Thread> = (0 until 12).map { index ->
                    Thread {
                        try {
                            val own = FakeVlcRuntime()
                            val player: VideoPlayer = newPlayer(own, loader)
                            val asset: Boolean = index % 2 == 0
                            prepare(player, if (asset) VideoSource.Asset("media/clip.mp4") else VideoSource.File(jar.path))
                            player.play()
                            if (index % 3 == 0) player.release()
                            Thread.sleep(2_000L)
                            player.release()
                        } catch (_: Throwable) {
                        }
                    }.apply { isDaemon = false; start() }
                }
                Thread.sleep(400L)
                println("R10-OK")
                System.out.flush()
                Runtime.getRuntime().exit(0)
                @Suppress("UNREACHABLE_CODE")
                threads.size
            }
            else -> error(mode)
        }
        println("R10-OK")
        System.exit(0)
    }
}

class VlcjEnvironmentR10AdversarialTest {

    private fun javaBinary(): String = File(File(System.getProperty("java.home"), "bin"), "java").path

    private data class Outcome(val exit: Int, val out: String, val err: String, val tempLeft: List<String>)

    private fun runChild(mode: String, tmpdir: File, scratch: File, ulimit: Int? = null, extra: List<String> = emptyList(), timeoutS: Long = 90L): Outcome {
        val java: List<String> = listOf(javaBinary(), "-Djava.io.tmpdir=${tmpdir.path}") + extra +
            listOf("-cp", System.getProperty("java.class.path"), VlcjEnvironmentR10Child::class.java.name, mode, scratch.path)
        val command: List<String> = if (ulimit != null) {
            listOf("/bin/sh", "-c", "ulimit -n $ulimit && exec " + java.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" })
        } else java
        val out: File = File(scratch, "out-$mode.txt")
        val err: File = File(scratch, "err-$mode.txt")
        val process: Process = ProcessBuilder(command).redirectOutput(out).redirectError(err).start()
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("child '$mode' did not exit within $timeoutS s (a hang at shutdown or in prepare); stdout=${out.readText()} stderr=${err.readText().take(2000)}")
        }
        return Outcome(process.exitValue(), out.readText(), err.readText(), tmpdir.list { _, n -> n.startsWith("kmptoolkit-video-") }?.toList().orEmpty())
    }

    private fun check(outcome: Outcome, what: String) {
        assertEquals(0, outcome.exit, "$what: exit ${outcome.exit}; stdout=${outcome.out}; stderr=${outcome.err.take(3000)}")
        assertTrue(outcome.out.contains("R10-OK"), "$what: no R10-OK in ${outcome.out}")
    }

    @Test
    fun `a jar asset with a missing temp directory ends in Error and the engine still plays a file`() {
        val scratch: File = Files.createTempDirectory("kmptoolkit-r10-env").toFile()
        try {
            val missing = File(scratch, "no-such-tmp")
            check(runChild("missing-tmpdir", missing, scratch), "missing temp dir")
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `a jar asset with a read-only temp directory ends in Error and leaves nothing behind`() {
        val scratch: File = Files.createTempDirectory("kmptoolkit-r10-env").toFile()
        val readOnly = File(scratch, "ro").apply { mkdirs(); setWritable(false, false) }
        try {
            if (readOnly.canWrite()) return // running as a user the permission bits do not bind (root)
            val outcome: Outcome = runChild("readonly-tmpdir", readOnly, scratch)
            check(outcome, "read-only temp dir")
            assertEquals(emptyList(), outcome.tempLeft)
        } finally {
            readOnly.setWritable(true, false)
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `with no file descriptor left a jar asset fails cleanly and the next prepare works`() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val scratch: File = Files.createTempDirectory("kmptoolkit-r10-env").toFile()
        try {
            val tmp = File(scratch, "tmp").apply { mkdirs() }
            check(runChild("fds", tmp, scratch, ulimit = 256), "descriptor exhaustion")
            assertEquals(emptyList(), tmp.list { _, n -> n.startsWith("kmptoolkit-video-") }?.toList().orEmpty())
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `players created and released on many threads while the JVM exits leave no temp copy and no hang`() {
        val scratch: File = Files.createTempDirectory("kmptoolkit-r10-env").toFile()
        try {
            val tmp = File(scratch, "tmp").apply { mkdirs() }
            repeat(3) {
                val outcome: Outcome = runChild("shutdown", tmp, scratch)
                check(outcome, "shutdown with live players")
                assertEquals(emptyList(), outcome.tempLeft, "temp copies survived the JVM exit")
                assertTrue(!outcome.err.contains("Exception in thread"), "uncaught exception at shutdown: ${outcome.err.take(2000)}")
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `the engine scenarios hold in a Turkish locale an odd time zone and a Latin-1 default charset`() {
        val scratch: File = Files.createTempDirectory("kmptoolkit-r10-env").toFile()
        try {
            val tmp = File(scratch, "tmp").apply { mkdirs() }
            val extra: List<String> = listOf("-Duser.language=tr", "-Duser.country=TR", "-Duser.timezone=Pacific/Kiritimati", "-Dfile.encoding=ISO-8859-1")
            check(runChild("shutdown", tmp, scratch, extra = extra), "locale shutdown")
            check(runChild("missing-tmpdir", File(scratch, "none"), scratch, extra = extra), "locale missing tmp")
        } finally {
            scratch.deleteRecursively()
        }
    }
}
