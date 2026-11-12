package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 8 (breaker at): a seeded long-run soak of the VLC engine behind the shared player on the libvlc model, with a
 * leak check at the end (native players freed once each and in order, libvlc instances freed, teardown threads gone,
 * temp copies gone, engines and players unreachable), and misuse of the public factory and exception types.
 * The soak length comes from the environment variable KMPTOOLKIT_SOAK_SECONDS (default 3).
 */
@OptIn(ToolkitInternalApi::class)
class VlcjSoakAndMisuseR8AdversarialTest {

    private fun soakSeconds(): Long = System.getenv("KMPTOOLKIT_SOAK_SECONDS")?.toLongOrNull() ?: 3L

    private fun teardownThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-vlcj-teardown" && it.isAlive }

    private fun copiesOnDisk(): Int =
        File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("kmptoolkit-video-") && f.name.endsWith(".mp4") }.orEmpty().size

    private fun jarLoader(jar: File): URLClassLoader {
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("assets/clip.mp4"))
            out.write(ByteArray(2_048) { it.toByte() })
            out.closeEntry()
        }
        return URLClassLoader(arrayOf(jar.toURI().toURL()), null)
    }

    @Test
    fun `a seeded soak of cycles and random calls frees every native handle thread and copy and keeps the player sane`() {
        val seed: Long = System.getenv("KMPTOOLKIT_SOAK_SEED")?.toLongOrNull() ?: 8L
        val random = Random(seed)
        val runtime = FakeVlcRuntime()
        val jar: File = Files.createTempFile("r8-soak-", ".jar").toFile().also { it.deleteOnExit() }
        val loader: URLClassLoader = jarLoader(jar)
        val threadsBefore: Int = teardownThreads()
        val copiesBefore: Int = copiesOnDisk()
        val weak: MutableList<WeakReference<Any>> = mutableListOf()
        var enginesDisposed = 0
        val deadline: Long = System.nanoTime() + soakSeconds() * 1_000_000_000L
        var steps = 0
        try {
            while (System.nanoTime() < deadline) {
                val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, classLoader = loader, createRuntime = { runtime })
                val player: VideoPlayer = io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer(
                    engine, VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default,
                )
                weak += WeakReference(engine); weak += WeakReference(player)
                val loads: MutableList<Job> = mutableListOf()
                runBlocking {
                    withTimeout(60_000L) {
                        // Every cycle really opens a native player first; the random calls then run on a loaded source.
                        player.prepare(if (random.nextBoolean()) VideoSource.File(TestClip.file.path) else VideoSource.Asset("assets/clip.mp4"))
                        repeat(random.nextInt(20, 120)) {
                            steps++
                            val current: FakeVlcPlayer? = runtime.players.lastOrNull()
                            when (random.nextInt(18)) {
                                0 -> loads += launch(Dispatchers.Default, CoroutineStart.DEFAULT) {
                                    player.prepare(if (random.nextBoolean()) VideoSource.File(TestClip.file.path) else VideoSource.Asset("assets/clip.mp4"))
                                }
                                1 -> player.play()
                                2 -> player.pause()
                                3 -> player.stop()
                                4 -> player.seekTo(random.nextLong(-50L, 5_000L))
                                5 -> player.seekForward(random.nextLong(-3_000L, 3_000L))
                                6 -> player.setPlaybackSpeed(random.nextFloat() * 40f)
                                7 -> player.setVolume(random.nextFloat() * 3f - 1f)
                                8 -> player.setMuted(random.nextBoolean())
                                9 -> player.setRepeatMode(RepeatMode.entries[random.nextInt(2)])
                                10 -> player.unload()
                                11 -> player.replay()
                                12 -> runCatching { current?.fire { playing() } }
                                13 -> runCatching { current?.fire { timeChanged(random.nextLong(0L, 2_500L)) } }
                                14 -> runCatching { current?.fire { finished() } }
                                15 -> runCatching { current?.fire { buffering(random.nextFloat() * 100f) } }
                                16 -> if (loads.isNotEmpty()) loads.removeAt(random.nextInt(loads.size)).cancel()
                                else -> runCatching { current?.fire { error() } }
                            }
                            if (steps % 20 == 0) {
                                val speed: Float = player.playbackSpeedFlow.value
                                assertTrue(speed in player.playbackSpeedRange, "speed $speed")
                                assertTrue(player.volumeFlow.value in 0f..1f)
                                assertTrue(player.playbackPositionFlow.value >= 0L)
                                (player.stateFlow.value as? VideoPlayerState.Playing)?.let { assertTrue(it.currentPosition >= 0L, "$it") }
                            }
                        }
                        loads.forEach { it.cancel() }
                        loads.forEach { it.join() }
                    }
                }
                player.release()
                engine.dispose()
                enginesDisposed++
            }
            // Every native player ever made: stopped at most once, released exactly once, nothing after release.
            val end: Long = System.nanoTime() + 30_000_000_000L
            fun settled(): Boolean = runtime.players.all { p -> p.calls.count { it == "release" } == 1 } && runtime.released == enginesDisposed
            while (!settled() && System.nanoTime() < end) Thread.sleep(25L)
            assertTrue(settled(), "players=${runtime.players.size} unreleased=${runtime.players.count { p -> p.calls.count { it == "release" } != 1 }} released=${runtime.released} engines=$enginesDisposed")
            runtime.players.forEachIndexed { i, p ->
                assertTrue(p.calls.count { it == "stop" } <= 1, "player $i stopped ${p.calls.count { it == "stop" }} times")
                assertEquals("release", p.calls.last(), "player $i: a native call after its release: ${p.calls.takeLast(4)}")
            }
            val tEnd: Long = System.nanoTime() + 20_000_000_000L
            while ((teardownThreads() > threadsBefore || copiesOnDisk() > copiesBefore) && System.nanoTime() < tEnd) Thread.sleep(50L)
            assertTrue(teardownThreads() <= threadsBefore, "teardown threads ${teardownThreads()} > $threadsBefore after $steps steps")
            assertTrue(copiesOnDisk() <= copiesBefore, "temp copies ${copiesOnDisk()} > $copiesBefore")
            // Reachability: the fake keeps its players, which only hold weak forwarders to the engines.
            var alive: Int = weak.size
            val gcEnd: Long = System.nanoTime() + 20_000_000_000L
            while (alive > 0 && System.nanoTime() < gcEnd) { System.gc(); Thread.sleep(50L); alive = weak.count { it.get() != null } }
            if (alive > 0) fail("$alive of ${weak.size} released engines or players are still reachable")
        } finally {
            runtime.shutdown()
            loader.close()
            jar.delete()
        }
    }

    @Test
    fun `the public factory with odd arguments settles every prepare and releases cleanly`() = runBlocking {
        withTimeout(120_000L) {
            val argSets: List<List<String>> = listOf(
                emptyList(), listOf(""), listOf("--no-such-option-r8"), listOf("\u0000"), listOf("a".repeat(100_000)),
                List(2_000) { "--quiet" },
            )
            val contexts: List<kotlin.coroutines.CoroutineContext> = listOf(
                kotlin.coroutines.EmptyCoroutineContext, Dispatchers.Unconfined, Dispatchers.IO,
            )
            for (args: List<String> in argSets) {
                for (context in contexts) {
                    val player: VideoPlayer = createVlcjVideoPlayer(VideoPlayerConfig(), context, args)
                    player.prepare(VideoSource.File(File("no-such-file-r8.mp4").path))
                    val state: VideoPlayerState = player.stateFlow.value
                    assertTrue(state is VideoPlayerState.Error || state is VideoPlayerState.Ready, "settled on $state")
                    player.play(); player.seekTo(Long.MAX_VALUE); player.setPlaybackSpeed(Float.NaN)
                    player.release(); player.release()
                }
            }
            val cancelled: Job = Job().also { it.cancel() }
            val player: VideoPlayer = createVlcjVideoPlayer(VideoPlayerConfig(), cancelled + Dispatchers.Default, emptyList())
            player.prepare(VideoSource.File("x"))
            player.release()
            assertEquals(isVlcAvailable(), isVlcAvailable())
        }
    }

    @Test
    fun `the public exceptions accept empty and huge messages and keep their causes`() {
        val cause = RuntimeException("c")
        for (message: String in listOf("", "x".repeat(1_000_000), "\u0000")) {
            assertEquals(message, VlcUnavailableException(message).message)
            assertEquals(cause, VlcUnavailableException(message, cause).cause)
            assertEquals(message, VlcPlaybackException(message).message)
            assertTrue(VlcPlaybackException(message).toString().isNotEmpty())
        }
        assertTrue(VlcUnavailableException("m") is IllegalStateException)
    }
}
