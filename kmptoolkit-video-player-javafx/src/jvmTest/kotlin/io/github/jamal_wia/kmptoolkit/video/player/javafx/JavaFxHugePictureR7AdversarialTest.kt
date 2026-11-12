package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (hostile media: huge pictures, real JavaFX): a 4096x2304 clip (2 s, solid red, 18 KB) is a realistic 4K
 * source whose every snapshot is a 37.7 MB array. The engine renders every picture into memory with a fresh
 * array per frame, in the default test heap: it must still play to its end, publish frames of the right size and
 * colour, report the size, and free everything on release, one player alone and two at once.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxHugePictureR7AdversarialTest {

    private val players: MutableList<VideoPlayer> = CopyOnWriteArrayList()
    private val uncaught: MutableList<String> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
    }

    private fun play(count: Int): List<Pair<JavaFxVideoEngine, VideoPlayer>> {
        assumeJavaFxMedia()
        return List(count) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 30_000L)
            val player: VideoPlayer = createVideoPlayer(engine)
            players += player
            engine to player
        }
    }

    @Test
    fun `a 4K clip plays to its end with frames of its size, alone and twice at once`() {
        for (count: Int in listOf(1, 2)) {
            val pairs: List<Pair<JavaFxVideoEngine, VideoPlayer>> = play(count)
            val frameCounts: List<AtomicInteger> = pairs.map { AtomicInteger() }
            val bad: MutableList<String> = CopyOnWriteArrayList()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val collectors = pairs.mapIndexed { n, (engine, _) ->
                thread(isDaemon = true, name = "r7-frames-$n") {
                    var last: VideoFrame? = null
                    while (!stop.get()) {
                        val frame: VideoFrame? = engine.frames.value
                        if (frame != null && frame !== last) {
                            last = frame
                            frameCounts[n].incrementAndGet()
                            if (frame.width != 4096 || frame.height != 2304 || frame.pixels.size != 4096 * 2304) bad += "frame ${frame.width}x${frame.height} of ${frame.pixels.size}"
                            val mid: Int = frame.pixels[(2304 / 2) * 4096 + 2048]
                            if (mid ushr 24 != 0xFF || (mid shr 16) and 0xFF < 200 || (mid shr 8) and 0xFF > 60) bad += "mid pixel ${Integer.toHexString(mid)}"
                        }
                        Thread.sleep(5L)
                    }
                }
            }
            try {
                runBlocking {
                    withTimeout(40_000L) { pairs.forEach { (_, p) -> p.prepare(VideoSource.Asset("red-4096x2304-2s.mp4")) } }
                }
                for ((n, pair) in pairs.withIndex()) {
                    val state: VideoPlayerState = pair.second.stateFlow.value
                    assertTrue(state is VideoPlayerState.Ready, "player $n of $count: $state")
                    assertEquals(VideoSize(4096, 2304), pair.second.videoSizeFlow.value, "player $n of $count: size")
                }
                pairs.forEach { it.second.play() }
                for ((n, pair) in pairs.withIndex()) {
                    awaitTrue(timeoutMs = 20_000L, message = { "player $n of $count never completed: ${pair.second.stateFlow.value}" }) {
                        pair.second.stateFlow.value is VideoPlayerState.Completed || pair.second.stateFlow.value is VideoPlayerState.Error
                    }
                    assertTrue(pair.second.stateFlow.value is VideoPlayerState.Completed, "player $n of $count: ${pair.second.stateFlow.value}")
                }
                assertEquals(emptyList(), bad.toList(), "frames of $count players")
                for (n in pairs.indices) assertTrue(frameCounts[n].get() >= 3, "player $n of $count published only ${frameCounts[n].get()} frames")
            } finally {
                stop.set(true)
                collectors.forEach { it.join(3_000L) }
                pairs.forEach { it.second.release() }
            }
        }
    }
}
