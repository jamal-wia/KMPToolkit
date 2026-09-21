package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import javafx.scene.media.MediaException
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The engine against real JavaFX Media and a real clip, per the [VideoPlaybackEngine] contract.
 * Skipped where the toolkit cannot start (see [assumeJavaFxMedia]).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxVideoEngineTest {

    private lateinit var engine: JavaFxVideoEngine
    private lateinit var listener: RecordingListener

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        engine = JavaFxVideoEngine()
        listener = RecordingListener()
        engine.setListener(listener)
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.release()
    }

    private fun load(source: VideoSource = VideoSource.Asset(TestClip.ASSET)) = runBlocking {
        withTimeout(LOAD_TIMEOUT_MS) { engine.load(source) }
    }

    private fun awaitFrame(predicate: (VideoFrame) -> Boolean = { true }): VideoFrame {
        var match: VideoFrame? = null
        awaitTrue(message = { "no matching frame; last = ${engine.frames.value?.describe()}" }) {
            match = engine.frames.value?.takeIf(predicate)
            match != null
        }
        return match!!
    }

    @Test
    fun loadingAnAssetReportsDurationAndPictureSize() {
        load()

        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L, "duration ${engine.durationMs()}")
        awaitTrue(message = { "size events: ${listener.events}" }) {
            EngineEvent.Size(VideoSize(TestClip.WIDTH, TestClip.HEIGHT)) in listener.events
        }
        assertTrue(listener.events.none { it is EngineEvent.Failed }, "${listener.events}")
    }

    @Test
    fun loadingAFileWorksLikeAnAsset() {
        load(VideoSource.File(TestClip.copyToTempFile().absolutePath))
        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L, "duration ${engine.durationMs()}")
    }

    @Test
    fun aFileThatIsNotVideoFailsToLoadAndLeavesNothingBehind() {
        val garbage: File = File.createTempFile("kmptoolkit-garbage", ".mp4").apply {
            deleteOnExit()
            writeBytes(ByteArray(4_096) { (it * 31).toByte() })
        }
        // JavaFX either rejects it (GStreamer on Linux/Windows) or never answers (AVFoundation on
        // macOS), which the engine turns into LoadTimedOut. Both are failures; a hang is not.
        engine = JavaFxVideoEngine(readyTimeoutMs = 3_000L).also { it.setListener(listener) }
        val error: Throwable = assertFails { load(VideoSource.File(garbage.absolutePath)) }
        assertTrue(
            error is JavaFxVideoPlayerException.LoadTimedOut || error is MediaException,
            "unexpected $error",
        )

        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertNull(engine.frames.value)
        engine.start() // tolerated, not playing anything
        assertTrue(listener.events.none { it is EngineEvent.Completed })
    }

    @Test
    fun anUnreachableRemoteFailsToLoad() {
        // Nothing listens on port 1: the connection is refused at once, no network needed.
        assertFailsWith<MediaException> { load(VideoSource.Remote("http://127.0.0.1:1/clip.mp4")) }
        assertEquals(0L, engine.durationMs())
    }

    @Test
    fun framesHaveThePictureSizeAndItsContent() {
        load()
        engine.start()

        val frame: VideoFrame = awaitFrame()
        assertEquals(TestClip.WIDTH, frame.width)
        assertEquals(TestClip.HEIGHT, frame.height)
        assertEquals(TestClip.WIDTH * TestClip.HEIGHT, frame.pixels.size)
        // The clip is solid red: the centre pixel is red, not a black or white placeholder.
        val argb: Int = frame.pixels[(TestClip.HEIGHT / 2) * TestClip.WIDTH + TestClip.WIDTH / 2]
        val red: Int = (argb shr 16) and 0xFF
        val green: Int = (argb shr 8) and 0xFF
        val blue: Int = argb and 0xFF
        assertTrue(red > 180 && green < 80 && blue < 80, "centre pixel #${Integer.toHexString(argb)}")
    }

    @Test
    fun aFrameIsProducedWithoutPlaying() {
        load()
        // Loaded but never started: a surface should still show the first picture.
        awaitFrame { it.width == TestClip.WIDTH }
    }

    @Test
    fun playingAdvancesThePositionAndPauseHoldsIt() {
        load()
        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 500L }

        engine.pause()
        Thread.sleep(200L) // let the pause land on the FX thread
        val held: Long = engine.positionMs()
        Thread.sleep(500L)
        assertTrue(abs(engine.positionMs() - held) <= 50L, "moved from $held to ${engine.positionMs()}")
    }

    @Test
    fun seekMovesThePlayheadWhilePaused() {
        load()
        engine.seekTo(1_500L)
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() in 1_400L..1_700L }
        Thread.sleep(300L)
        assertTrue(engine.positionMs() in 1_400L..1_700L, "position ${engine.positionMs()}")
    }

    @Test
    fun playingToTheEndCompletesOnce() {
        load()
        engine.seekTo(2_600L)
        engine.start()
        awaitTrue(message = { "events ${listener.events}" }) { EngineEvent.Completed in listener.events }
        Thread.sleep(300L)
        assertEquals(1, listener.events.count { it == EngineEvent.Completed })
    }

    @Test
    fun startAfterCompletionPlaysFromTheBeginning() {
        load()
        engine.seekTo(2_700L)
        engine.start()
        awaitTrue(message = { "events ${listener.events}" }) { EngineEvent.Completed in listener.events }

        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() in 1L..1_000L }
    }

    @Test
    fun seekAfterCompletionStaysPaused() {
        load()
        engine.seekTo(2_600L)
        engine.start()
        awaitTrue(message = { "events ${listener.events}" }) { EngineEvent.Completed in listener.events }

        // JavaFX itself leaves its status at PLAYING here; on a backend that honours it, the seek
        // below would resume playback. The engine must have paused it.
        awaitTrue(message = { "status ${inspect().status}" }) { inspect().status == "PAUSED" }

        // The player reports Paused after this seek; the engine must not quietly play on from here.
        engine.seekTo(1_000L)
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() in 900L..1_100L }
        Thread.sleep(700L)
        assertTrue(engine.positionMs() in 900L..1_100L, "moved on to ${engine.positionMs()}")
        assertEquals("PAUSED", inspect().status)
        assertEquals(1, listener.events.count { it == EngineEvent.Completed }, "${listener.events}")

        // And start() resumes from the seek target, not from the beginning.
        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 1_300L }
    }

    @Test
    fun loopingRestartsWithoutCompleting() {
        load()
        engine.setLooping(true)
        engine.seekTo(2_500L)
        engine.start()
        // Past the end once: the playhead wraps around to the start.
        awaitTrue(timeoutMs = 6_000L, message = { "position ${engine.positionMs()}" }) {
            engine.positionMs() in 1L..1_500L
        }
        Thread.sleep(300L)
        assertTrue(listener.events.none { it == EngineEvent.Completed }, "${listener.events}")
        // Still decoding pictures after the wrap.
        val before: VideoFrame? = engine.frames.value
        awaitFrame { it !== before }
    }

    @Test
    fun settingsGivenBeforeLoadAreApplied() {
        engine.setLooping(true)
        engine.setVolume(0f)
        engine.setSpeed(2f)
        load()

        // The settings are posted to the FX thread; read them back from JavaFX once they land.
        awaitTrue(message = { "settings ${inspect().describe()}" }) {
            inspect().let { it.volume == 0.0 && it.rate == 2.0 && it.cycleCount == INDEFINITE }
        }

        // And they take effect: at double speed a second of wall clock covers about two of media
        // (normal speed would cover one; the clip is 3 s, so this ends before the loop wraps).
        engine.seekTo(0L)
        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 100L }
        val from: Long = engine.positionMs()
        Thread.sleep(1_000L)
        val covered: Long = engine.positionMs() - from
        assertTrue(covered >= 1_500L, "covered $covered ms of media in 1 s at speed 2")

        // Looping: past the end, the playhead wraps around without completing.
        engine.seekTo(2_600L)
        awaitTrue(timeoutMs = 6_000L, message = { "position ${engine.positionMs()}" }) {
            engine.positionMs() in 1L..1_500L
        }
        assertTrue(listener.events.none { it == EngineEvent.Completed }, "${listener.events}")
    }

    @Test
    fun speedSetWhilePausedHoldsAfterResuming() {
        load()
        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 100L }
        engine.pause()
        // Set while paused: on macOS, play() alone would restart the native player at speed 1.
        engine.setSpeed(2f)
        engine.seekTo(0L)
        engine.start()
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 100L }
        val from: Long = engine.positionMs()
        Thread.sleep(1_000L)
        val covered: Long = engine.positionMs() - from
        assertTrue(covered >= 1_500L, "covered $covered ms of media in 1 s at speed 2")
    }

    @Test
    fun settingsChangedAfterLoadReachThePlayer() {
        load()
        engine.setVolume(0.25f)
        engine.setSpeed(0.5f)
        engine.setLooping(true)
        awaitTrue(message = { "settings ${inspect().describe()}" }) {
            inspect().let { it.volume == 0.25 && it.rate == 0.5 && it.cycleCount == INDEFINITE }
        }

        engine.setLooping(false)
        awaitTrue(message = { "settings ${inspect().describe()}" }) { inspect().cycleCount == 1 }
    }

    @Test
    fun releaseIsIdempotentAndClearsEverything() {
        load()
        engine.start()
        awaitFrame()

        engine.release()
        engine.release()

        assertNull(engine.frames.value)
        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertEquals(0L, engine.bufferedPositionMs())
        // Transport calls after release are tolerated.
        engine.start()
        engine.seekTo(100L)
        engine.pause()
    }

    @Test
    fun nothingReachesTheListenerOrTheFramesAfterRelease() {
        load()
        engine.seekTo(2_500L)
        engine.start()
        awaitFrame()

        engine.release()
        val eventsAtRelease: Int = listener.events.size
        // Longer than the rest of the clip: its end, and any queued frame, would arrive in here.
        Thread.sleep(1_000L)
        assertEquals(eventsAtRelease, listener.events.size, "${listener.events}")
        assertNull(engine.frames.value)
    }

    @Test
    fun loadAfterReleaseWorks() {
        load()
        engine.release()

        load()
        engine.start()
        awaitFrame()
        assertTrue(abs(engine.durationMs() - TestClip.DURATION_MS) <= 100L)
    }

    @Test
    fun loadingANewSourceReplacesTheOldOne() {
        load()
        engine.seekTo(2_500L)
        engine.start()
        load(VideoSource.File(TestClip.copyToTempFile().absolutePath))
        Thread.sleep(1_000L) // the first source would have completed in here
        assertTrue(listener.events.none { it == EngineEvent.Completed }, "${listener.events}")
    }

    @Test
    fun cancellingALoadLeavesNothingPlayable() = runBlocking {
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            engine.load(VideoSource.Asset(TestClip.ASSET))
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)

        Thread.sleep(500L) // anything the cancelled load left running would report in here
        assertEquals(0L, engine.durationMs())
        assertNull(engine.frames.value)
        assertTrue(listener.events.isEmpty(), "${listener.events}")

        // And the engine is still usable.
        withTimeout(LOAD_TIMEOUT_MS) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(engine.durationMs() > 0L)
    }

    @Test
    fun releaseDuringALoadFailsThatLoad() = runBlocking {
        val loading = async(Dispatchers.Default) {
            runCatching { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        }
        engine.release()
        val result: Result<Unit> = withTimeout(LOAD_TIMEOUT_MS) { loading.await() }
        // Either the release landed before the load began (the load then succeeds on a clean
        // engine) or during it — and then the load must not report success.
        result.exceptionOrNull()?.let { error ->
            assertTrue(
                error is VideoPlayerReleasedException || error is CancellationException,
                "unexpected $error",
            )
            assertEquals(0L, engine.durationMs())
        }
        Unit
    }

    @Test
    fun everyFrameOwnsItsPixels() {
        load()
        engine.start()
        // A consumer may hold any frame for as long as it likes (the flow is conflated): no two
        // frames may share an array, or a later frame would be written over one still being drawn.
        val frames: MutableList<VideoFrame> = mutableListOf(awaitFrame())
        repeat(5) { frames += awaitFrame { candidate -> frames.none { it === candidate } } }
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                assertTrue(frames[i].pixels !== frames[j].pixels, "frames $i and $j share an array")
            }
        }
        // The first frame still holds its picture after the others were produced.
        val centre: Int = frames.first().pixels[(TestClip.HEIGHT / 2) * TestClip.WIDTH + TestClip.WIDTH / 2]
        assertTrue((centre shr 16) and 0xFF > 180, "centre pixel #${Integer.toHexString(centre)}")
        assertNotNull(engine.frames.value)
    }

    private fun inspect(): FxPlayback.Inspection =
        assertNotNull(runBlocking { engine.inspectPlayback() }, "nothing loaded")

    private fun FxPlayback.Inspection.describe(): String =
        "volume=$volume rate=$rate cycleCount=$cycleCount status=$status"

    private fun VideoFrame.describe(): String = "${width}x$height"

    private companion object {
        const val LOAD_TIMEOUT_MS: Long = 10_000L

        /** `MediaPlayer.INDEFINITE`, without touching a JavaFX class from a test constant. */
        const val INDEFINITE: Int = -1
    }
}
