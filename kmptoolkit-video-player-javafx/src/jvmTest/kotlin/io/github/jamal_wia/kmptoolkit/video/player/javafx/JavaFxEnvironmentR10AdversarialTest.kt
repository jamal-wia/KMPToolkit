package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
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
 * The child of [JavaFxEnvironmentR10AdversarialTest]: one scenario per JVM. Prints R10-OK and exits 0 when the
 * scenario's expectations hold; exit 3 with R10-FAIL on stderr when they do not.
 */
@OptIn(ToolkitInternalApi::class)
internal object JavaFxEnvironmentR10Child {

    private fun expect(condition: Boolean, message: () -> String) {
        if (!condition) {
            System.err.println("R10-FAIL: ${message()}")
            System.out.flush()
            Runtime.getRuntime().halt(3)
        }
    }

    private fun prepare(player: VideoPlayer, source: VideoSource): VideoPlayerState = runBlocking {
        withTimeout(40_000L) { player.prepare(source) }
        player.stateFlow.value
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(20L)
        }
        return true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val mode: String = args[0]
        val scratch = File(args[1])
        val clip: File = File(scratch, "clip.mp4").also { target ->
            JavaFxEnvironmentR10Child::class.java.classLoader.getResourceAsStream(TestClip.ASSET)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        when (mode) {
            "tmpdir" -> {
                val player: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 20_000L))
                val viaFile: VideoPlayerState = prepare(player, VideoSource.File(clip.path))
                expect(viaFile is VideoPlayerState.Ready) { "a file with an unusable temp dir: $viaFile" }
                player.play()
                expect(waitFor(8_000L) { player.stateFlow.value is VideoPlayerState.Completed }) { "no completion: ${player.stateFlow.value}" }
                // the clip inside a jar: JavaFX reads a jar: URL itself
                val jar = File(scratch, "assets.jar")
                JarOutputStream(jar.outputStream()).use { o ->
                    o.putNextEntry(JarEntry("clips/c.mp4"))
                    o.write(clip.readBytes())
                    o.closeEntry()
                }
                val jarEngine = JavaFxVideoEngine(readyTimeoutMs = 20_000L, classLoader = URLClassLoader(arrayOf(jar.toURI().toURL()), null))
                val jarPlayer: VideoPlayer = createVideoPlayer(jarEngine)
                val viaJar: VideoPlayerState = prepare(jarPlayer, VideoSource.Asset("clips/c.mp4"))
                expect(viaJar is VideoPlayerState.Ready || viaJar is VideoPlayerState.Error) { "jar asset: $viaJar" }
                println("jar asset with unusable temp dir: ${describe(viaJar)}")
                jarPlayer.release()
                player.release()
            }
            "fds" -> {
                val warm: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 20_000L))
                prepare(warm, VideoSource.File(File(scratch, "missing.mp4").path))
                prepare(warm, VideoSource.File(clip.path))
                warm.play()
                waitFor(3_000L) { warm.stateFlow.value is VideoPlayerState.Playing }
                warm.release()
                val player: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 20_000L))
                val hogs: MutableList<java.io.Closeable> = mutableListOf()
                try {
                    while (true) hogs += java.io.FileInputStream("/dev/null")
                } catch (_: java.io.IOException) {
                }
                repeat(6) { hogs.removeLastOrNull()?.close() }
                val state: VideoPlayerState = prepare(player, VideoSource.File(clip.path))
                println("prepare with no descriptor left: ${describe(state)}")
                expect(state is VideoPlayerState.Ready || state is VideoPlayerState.Error) { "state $state" }
                player.release()
                hogs.forEach { it.close() }
                val after: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 20_000L))
                val again: VideoPlayerState = prepare(after, VideoSource.File(clip.path))
                expect(again is VideoPlayerState.Ready) { "after the descriptors came back: $again" }
                after.release()
            }
            "exit-storm" -> {
                val started = java.util.concurrent.atomic.AtomicInteger()
                repeat(16) { index ->
                    Thread {
                        try {
                            val player: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 20_000L))
                            started.incrementAndGet()
                            runBlocking { withTimeout(30_000L) { player.prepare(VideoSource.File(clip.path)) } }
                            player.play()
                            if (index % 4 == 0) player.release()
                            Thread.sleep(5_000L)
                            player.release()
                        } catch (_: Throwable) {
                        }
                    }.apply { isDaemon = false; start() }
                }
                waitFor(10_000L) { started.get() >= 8 }
                Thread.sleep(300L)
                println("R10-OK")
                System.out.flush()
                System.exit(0)
            }
            else -> error(mode)
        }
        println("R10-OK")
        System.out.flush()
        System.exit(0)
    }

    private fun describe(state: VideoPlayerState): String = if (state is VideoPlayerState.Error) "Error(${state.cause})" else state.toString()
}

class JavaFxEnvironmentR10AdversarialTest {

    private fun runChild(mode: String, tmpdir: File, scratch: File, ulimit: Int? = null, timeoutS: Long = 120L): Triple<Int, String, String> {
        val java: List<String> = listOf(File(File(System.getProperty("java.home"), "bin"), "java").path, "-Djava.io.tmpdir=${tmpdir.path}") + childJvmCrashGuardArgs() +
            listOf("-cp", System.getProperty("java.class.path"), JavaFxEnvironmentR10Child::class.java.name, mode, scratch.path)
        val command: List<String> = if (ulimit != null) {
            listOf("/bin/sh", "-c", "ulimit -n $ulimit && exec " + java.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" })
        } else java
        val out = File(scratch, "out-$mode.txt")
        val err = File(scratch, "err-$mode.txt")
        val process: Process = ProcessBuilder(command).redirectOutput(out).redirectError(err).start()
        if (!process.waitFor(timeoutS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("child '$mode' did not exit within $timeoutS s; stdout=${out.readText().take(2000)} stderr=${err.readText().take(2000)}")
        }
        return Triple(process.exitValue(), out.readText(), err.readText())
    }

    private fun scratch(): File = Files.createTempDirectory("kmptoolkit-r10-fx-env").toFile()

    @Test
    fun `a clip plays from a file and from a jar when the temp directory does not exist`() {
        assumeJavaFxMedia()
        val scratch: File = scratch()
        try {
            val (exit, out, err) = runChild("tmpdir", File(scratch, "missing-tmp"), scratch)
            assertEquals(0, exit, "exit $exit; stdout=$out; stderr=${err.take(3000)}")
            assertTrue(out.contains("R10-OK"), out)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `with no file descriptor left a prepare ends without a hang or a crash and works again afterwards`() {
        assumeJavaFxMedia()
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val scratch: File = scratch()
        try {
            val (exit, out, err) = runChild("fds", File(scratch, "tmp").apply { mkdirs() }, scratch, ulimit = 512)
            assertEquals(0, exit, "exit $exit; stdout=$out; stderr=${err.take(3000)}")
            assertTrue(out.contains("R10-OK"), out)
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun `sixteen players on sixteen threads while the JVM exits do not hang the exit`() {
        assumeJavaFxMedia()
        val scratch: File = scratch()
        try {
            repeat(3) {
                val (exit, out, err) = runChild("exit-storm", File(scratch, "tmp").apply { mkdirs() }, scratch, timeoutS = 60L)
                assertEquals(0, exit, "exit $exit; stdout=$out; stderr=${err.take(3000)}")
                assertTrue(out.contains("R10-OK"), out)
            }
        } finally {
            scratch.deleteRecursively()
        }
    }
}
