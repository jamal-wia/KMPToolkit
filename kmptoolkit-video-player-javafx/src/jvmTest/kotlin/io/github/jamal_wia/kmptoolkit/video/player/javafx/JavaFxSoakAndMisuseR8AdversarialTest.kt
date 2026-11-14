package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.BeforeTest
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
 * Round 8 (breaker at): a seeded soak of load, random calls and release cycles on real JavaFX, with a leak check at
 * the end (threads, files in the temporary directory, engines and players unreachable), and misuse of the public
 * factory. The soak length comes from kmptoolkit.test.soak.seconds (default 8): the default suite stays short.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSoakAndMisuseR8AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private fun soakSeconds(): Long = System.getProperty("kmptoolkit.test.soak.seconds")?.toLongOrNull() ?: 8L

    private fun tempNames(): Set<String> = File(System.getProperty("java.io.tmpdir")).list().orEmpty().toSet()

    private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

    private fun namedThreads(prefix: String): Int = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(prefix) }

    private fun threadNames(): Map<String, Int> =
        Thread.getAllStackTraces().keys.filter { it.isAlive }.groupingBy { it.name.replace(Regex("[0-9]+"), "#") }.eachCount()

    private fun cycle(random: Random, weak: MutableList<WeakReference<Any>>, trace: StringBuilder = StringBuilder()) {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 20L), Dispatchers.Default)
        weak += WeakReference(engine); weak += WeakReference(player)
        val loads: MutableList<Job> = mutableListOf()
        try {
            runBlocking {
                withTimeout(60_000L) {
                    player.prepare(VideoSource.Asset(TestClip.AUDIO_ASSET))
                    repeat(random.nextInt(5, 25)) {
                        val op: Int = random.nextInt(14)
                        trace.append(op).append(',')
                        when (op) {
                            0 -> player.play()
                            1 -> player.pause()
                            2 -> player.stop()
                            3 -> player.seekTo(random.nextLong(-50L, 4_000L))
                            4 -> player.seekForward(random.nextLong(-2_000L, 2_000L))
                            5 -> player.setPlaybackSpeed(random.nextFloat() * 5f)
                            6 -> player.setVolume(random.nextFloat() * 2f)
                            7 -> player.setMuted(random.nextBoolean())
                            8 -> player.setRepeatMode(RepeatMode.entries[random.nextInt(2)])
                            9 -> player.replay()
                            10 -> player.unload()
                            11 -> loads += launch(Dispatchers.Default, CoroutineStart.DEFAULT) { player.prepare(VideoSource.Asset(TestClip.AUDIO_ASSET)) }
                            12 -> if (loads.isNotEmpty()) loads.removeAt(random.nextInt(loads.size)).cancel()
                            else -> Thread.sleep(random.nextLong(0L, 60L))
                        }
                        assertTrue(player.playbackSpeedFlow.value in player.playbackSpeedRange)
                        assertTrue(player.playbackPositionFlow.value >= 0L)
                        (player.stateFlow.value as? VideoPlayerState.Playing)?.let { assertTrue(it.currentPosition >= 0L, "$it") }
                    }
                    loads.forEach { it.cancel() }
                    loads.forEach { it.join() }
                }
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun `a seeded soak of load call and release cycles leaves no thread file or engine behind`() {
        val random = Random(System.getProperty("kmptoolkit.test.soak.seed")?.toLongOrNull() ?: 8L)
        val weak: MutableList<WeakReference<Any>> = mutableListOf()
        cycle(random, weak) // warm-up: the toolkit's and the media stack's own one-off threads and files
        weak.clear()
        val threadsBefore: Int = liveThreads()
        val filesBefore: Set<String> = tempNames()
        val namesBefore: Map<String, Int> = threadNames()
        val deadline: Long = System.nanoTime() + soakSeconds() * 1_000_000_000L
        var cycles = 0
        while (System.nanoTime() < deadline) {
            val trace = StringBuilder()
            cycle(random, weak, trace)
            cycles++
        }
        val end: Long = System.nanoTime() + 20_000_000_000L
        // JavaFX Media starts a daemon java.util.Timer thread per MediaPlayer that played (a raw MediaPlayer does too);
        // the JDK reaps it when the Timer is collected, so the leak check lets the collector run first.
        while (liveThreads() - threadsBefore > 8 && System.nanoTime() < end) { System.gc(); Thread.sleep(100L) }
        val grown: Map<String, Int> = threadNames().mapValues { (name, n) -> n - (namesBefore[name] ?: 0) }.filterValues { it > 0 }
        assertTrue(liveThreads() - threadsBefore <= 8, "threads grew ${liveThreads() - threadsBefore} over $cycles cycles: $grown")
        val created: List<String> = (tempNames() - filesBefore).sorted()
        assertEquals(emptyList(), created, "files left behind by $cycles cycles")
        var alive: Int = weak.size
        val gcEnd: Long = System.nanoTime() + 30_000_000_000L
        while (alive > 0 && System.nanoTime() < gcEnd) { System.gc(); Thread.sleep(100L); alive = weak.count { it.get() != null } }
        if (alive > 0) fail("$alive of ${weak.size} released engines or players are still reachable after $cycles cycles")
    }

    @Test
    fun `the public factory with odd contexts and sources settles every prepare and releases cleanly`() = runBlocking {
        withTimeout(120_000L) {
            val contexts: List<kotlin.coroutines.CoroutineContext> = listOf(
                kotlin.coroutines.EmptyCoroutineContext, Dispatchers.Unconfined, Dispatchers.IO,
                Job().also { it.cancel() } + Dispatchers.Default,
            )
            val sources: List<VideoSource> = listOf(
                VideoSource.File(""), VideoSource.File("\u0000"), VideoSource.Asset(""), VideoSource.Asset("nope/r8.mp4"),
                VideoSource.Remote(""), VideoSource.Remote("http://127.0.0.1:1/x.mp4"),
                VideoSource.Remote("http://127.0.0.1:1/x.mp4", mapOf("A" to "b")),
            )
            for (context in contexts) {
                val player: VideoPlayer = createJavaFxVideoPlayer(VideoPlayerConfig(), context)
                for (source in sources) {
                    player.prepare(source)
                    val state: VideoPlayerState = player.stateFlow.value
                    assertTrue(state is VideoPlayerState.Error || state is VideoPlayerState.Ready || state == VideoPlayerState.Idle, "$source settled on $state")
                    player.play(); player.seekTo(Long.MAX_VALUE); player.setPlaybackSpeed(Float.NaN); player.stop()
                }
                player.release(); player.release()
            }
            val headers: VideoPlayerState = createJavaFxVideoPlayer().also { it.prepare(VideoSource.Remote("http://x/y.mp4", mapOf("A" to "b"))) }.stateFlow.value
            assertTrue(headers is VideoPlayerState.Error && headers.cause is JavaFxVideoPlayerException.HeadersNotSupported, "$headers")
        }
    }
}
