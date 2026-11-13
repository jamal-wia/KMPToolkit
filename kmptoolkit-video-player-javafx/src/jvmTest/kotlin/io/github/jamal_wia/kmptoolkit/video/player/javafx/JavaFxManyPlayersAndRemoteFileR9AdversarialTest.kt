package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (hostile resources): many native players alive at once on one toolkit, each prepared and played from its own thread
 * and released from another set of threads, and a `Remote` source that is a `file:` URL (the docs say a `Remote` URL goes to
 * JavaFX as it is). After the crowd a fresh player must still prepare, play and complete, and the toolkit must not have grown
 * a thread per player for good.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxManyPlayersAndRemoteFileR9AdversarialTest {

    @Test
    fun `thirty players prepared played and released from sixty threads leave a toolkit that still plays`() {
        assumeJavaFxMedia()
        val count = 30
        val players: MutableList<VideoPlayer> = Collections.synchronizedList(mutableListOf())
        val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val ready = CountDownLatch(count)
        val threadsBefore: Int = Thread.getAllStackTraces().size
        val workers: List<Thread> = (0 until count).map { i: Int ->
            Thread {
                val player: VideoPlayer = createJavaFxVideoPlayer()
                players += player
                try {
                    runBlocking { withTimeout(60_000L) { player.prepare(VideoSource.Asset(TestClip.AUDIO_ASSET)) } }
                    player.setPlaybackSpeed(if (i % 2 == 0) 1f else 2f)
                    player.play()
                    if (player.stateFlow.value !is VideoPlayerState.Playing) failures += "player $i: ${player.stateFlow.value} after play"
                } catch (t: Throwable) {
                    failures += "player $i: ${t::class.java.simpleName}: ${t.message?.take(100)}"
                } finally {
                    ready.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue(ready.await(120L, java.util.concurrent.TimeUnit.SECONDS), "the crowd did not finish preparing")
        fun movedCount(): Int = players.toList().count { it.playbackPositionFlow.value > 0L || it.stateFlow.value is VideoPlayerState.Completed }
        awaitTrue(timeoutMs = 25_000L, message = { "only ${movedCount()} of $count players moved" }) { movedCount() >= count * 2 / 3 }
        val moving: Int = movedCount()
        // Release from a different set of threads, all at once.
        val release = CountDownLatch(1)
        val releasers: List<Thread> = players.toList().map { p: VideoPlayer -> Thread { release.await(); p.release() }.apply { isDaemon = true; start() } }
        release.countDown()
        releasers.forEach { it.join(30_000L) }
        assertTrue(releasers.none { it.isAlive }, "a release hung")
        assertEquals(emptyList(), failures.toList(), "players that failed ($moving of $count had moved after the wait)")
        assertTrue(moving >= count * 2 / 3, "only $moving of $count players had moved")
        System.gc()
        Thread.sleep(500L)
        val fresh: VideoPlayer = createJavaFxVideoPlayer()
        try {
            runBlocking { withTimeout(30_000L) { fresh.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            fresh.setPlaybackSpeed(3f)
            fresh.play()
            awaitTrue(timeoutMs = 10_000L, message = { "the fresh player never completed: ${fresh.stateFlow.value}" }) { fresh.stateFlow.value is VideoPlayerState.Completed }
        } finally {
            fresh.release()
        }
        assertTrue(Thread.getAllStackTraces().size < threadsBefore + 150, "threads grew from $threadsBefore to ${Thread.getAllStackTraces().size}")
    }

    @Test
    fun `a Remote source that is a file url plays as the docs say it is passed to JavaFX as is`() {
        assumeJavaFxMedia()
        val file: File = TestClip.copyToTempFile()
        val player: VideoPlayer = createJavaFxVideoPlayer()
        try {
            runBlocking { withTimeout(30_000L) { player.prepare(VideoSource.Remote(file.toURI().toString())) } }
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "${player.stateFlow.value}")
            player.play()
            awaitTrue(timeoutMs = 8_000L, message = { "never moved: ${player.stateFlow.value}" }) { player.playbackPositionFlow.value > 300L }
        } finally {
            player.release()
        }
    }
}
