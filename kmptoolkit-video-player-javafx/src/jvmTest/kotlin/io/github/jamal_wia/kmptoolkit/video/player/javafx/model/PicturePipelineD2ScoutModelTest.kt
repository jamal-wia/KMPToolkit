package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 scout (executor ko), angle (b), the picture pipeline, at `FxPlayback` over the composed scripted player (virtual time, no
 * media). The oracle is the documented text of `docs/kmptoolkit-video-player-javafx/03-guide.md` "Frames and the picture" and
 * `05-platform-notes.md` "CPU cost":
 *
 * - "copies the picture at most 30 times per second" while playing, "a paused player costs no copying at all" and "while paused it
 *   copies only after something changed it (the first picture after `prepare`, a seek)";
 * - "32-bit ARGB at the video's own resolution, in an array of its own that the engine never writes again";
 * - "the array is new for every frame on purpose" (`width x height x 4` bytes, 8.3 MB at 1080p);
 * - "the pulse runs only while there is a picture to copy: it stops for audio-only sources ... and starts again on `start()`, a seek,
 *   or a picture that appears later".
 *
 * The decoder side is a [SizedPlayer]: the composed scripted player under a wrapper that can change the size JavaFX reports
 * mid-play and that stamps every snapshot with a number of its own, so a frame that the engine wrote again, or published twice,
 * shows in the pixels.
 */
@OptIn(ToolkitInternalApi::class)
class PicturePipelineD2ScoutModelTest {

    /** One published frame as the sink saw it, with the virtual time and the stamp its pixels held at publication. */
    private class Seen(val frame: VideoFrame, val atNanos: Long, val stampAtPublication: Int)

    private class RecordingSink(private val host: VirtualFxHost) : FxPlayback.Sink {
        val frames: MutableList<Seen> = CopyOnWriteArrayList()
        val sizes: MutableList<VideoSize?> = CopyOnWriteArrayList()
        override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) = Unit
        override fun onSeekApplied() = Unit
        override fun onVideoSize(size: VideoSize?) {
            sizes += size
        }
        override fun onBuffering(isBuffering: Boolean) = Unit
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) = Unit
        override fun onFrame(frame: VideoFrame) {
            frames += Seen(frame, host.elapsedNanos, frame.pixels[0] and STAMP_MASK)
        }
    }

    /** The composed scripted player under a size override and a stamping snapshot. FX thread only, like the port. */
    private class SizedPlayer(val inner: ScriptedNativePlayer, private var forced: Pair<Int, Int>?) : NativePlayer by inner {
        /** Every array a snapshot was asked to fill, in call order (identity matters, so no copies). */
        val snapshotTargets: MutableList<IntArray> = CopyOnWriteArrayList()
        private var stamp: Int = 0

        override val width: Int get() = forced?.first ?: inner.width
        override val height: Int get() = forced?.second ?: inner.height

        /** Changes the size JavaFX reports and tells the engine the way the JavaFX size listeners do. */
        fun resize(w: Int, h: Int) {
            forced = w to h
            checkNotNull(inner.events).onSize()
        }

        override fun snapshotInto(pixels: IntArray, width: Int, height: Int) {
            snapshotTargets += pixels
            inner.snapshotInto(pixels, width, height)
            // A decoded picture is opaque; the transparent "nothing yet" answer of the picture part stays as it is.
            if (pixels[0] ushr 24 != 0) {
                stamp++
                pixels.fill(OPAQUE or stamp, 0, width * height)
            }
        }
    }

    private class SizedBackend(private val composed: ComposedBackend, private val initial: (String) -> Pair<Int, Int>?) : NativeBackend {
        val sized: MutableList<SizedPlayer> = CopyOnWriteArrayList()
        override val host get() = composed.host
        override fun probe(uri: String): NativeSource = composed.probe(uri)
        override fun create(source: NativeSource): NativePlayer {
            val uri: String = (source as ScriptedNativeSource).uri
            val player = SizedPlayer(composed.create(source) as ScriptedNativePlayer, initial(uri))
            sized += player
            return player
        }
    }

    private fun FxModelScope.backend(clip: ClipModel = ClipModel(), pulsePeriodNanos: Double = PictureModel.DEFAULT_PULSE_PERIOD_NANOS, initial: (String) -> Pair<Int, Int>? = { null }): SizedBackend =
        SizedBackend(composeAll(ScriptedBackend(host, clip = clip), pulsePeriodNanos = pulsePeriodNanos), initial)

    private fun FxModelScope.open(sink: RecordingSink, backend: SizedBackend, maxFrameRate: Int = 30, uri: String = "scripted:${TestClip.ASSET}"): FxPlayback {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        return runBlocking {
            val opening = async { FxPlayback.open(uri, sink, maxFrameRate, 15_000L, backend, onCreated = {}) }
            while (!opening.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
                if (!opening.isCompleted && System.nanoTime() - deadline > 0L) {
                    opening.cancel()
                    throw AssertionError("open did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            opening.await()
        }
    }

    private fun assertEveryFrameIsItsOwnUnwrittenArray(frames: List<Seen>) {
        val identities: IdentityHashMap<IntArray, Boolean> = IdentityHashMap()
        for ((index: Int, seen: Seen) in frames.withIndex()) {
            assertTrue(identities.put(seen.frame.pixels, true) == null, "frame $index was published with an array that an earlier frame already held")
            assertEquals(seen.frame.width * seen.frame.height, seen.frame.pixels.size, "frame $index holds ${seen.frame.pixels.size} pixels for ${seen.frame.width}x${seen.frame.height}")
            val stampNow: Int = seen.frame.pixels[0] and STAMP_MASK
            assertEquals(seen.stampAtPublication, stampNow, "frame $index was written again after it was published")
            assertTrue(seen.frame.pixels.all { it == seen.frame.pixels[0] }, "frame $index is not uniform: a later snapshot wrote into a part of it")
        }
        val stamps: Set<Int> = frames.map { it.stampAtPublication }.toSet()
        assertEquals(frames.size, stamps.size, "two frames carry the same snapshot stamp, so one snapshot was published twice")
    }

    @Test
    fun aSizeChangeWhilePlayingPublishesFramesOfTheNewSizeInArraysOfTheirOwn() = fxModel {
        val backend: SizedBackend = backend()
        val sink = RecordingSink(host)
        val playback: FxPlayback = open(sink, backend)
        advanceBy(50)
        playback.start()
        advanceBy(700)
        val player: SizedPlayer = backend.sized.last()
        onFx { player.resize(128, 96) }
        advanceBy(700)
        // The same pixel count with other sides: a scratch array of the old size must not be taken for the new one.
        onFx { player.resize(96, 128) }
        advanceBy(700)
        onFx { player.resize(64, 48) }
        advanceBy(700)
        playback.dispose()
        advanceBy(50)
        val sidesSeen: List<Pair<Int, Int>> = sink.frames.map { it.frame.width to it.frame.height }.distinct()
        assertEquals(listOf(64 to 48, 128 to 96, 96 to 128), sidesSeen, "the frames did not follow the size JavaFX reported: $sidesSeen")
        assertEquals(listOf<VideoSize?>(VideoSize(64, 48), VideoSize(128, 96), VideoSize(96, 128), VideoSize(64, 48)), sink.sizes, "sizes reported: ${sink.sizes}")
        assertEveryFrameIsItsOwnUnwrittenArray(sink.frames)
        // Each size is shown for 700 ms at 30 fps: a frame of the new size follows the change promptly.
        for (change: Pair<Int, Int> in listOf(128 to 96, 96 to 128)) {
            assertTrue(sink.frames.count { (it.frame.width to it.frame.height) == change } >= 10, "the size $change got fewer than 10 frames in 700 ms")
        }
    }

    @Test
    fun noArrayHandedToASnapshotWasPublishedBeforeThroughSizeChangesAStallAndASeek() = fxModel {
        val backend: SizedBackend = backend()
        val sink = RecordingSink(host)
        val playback: FxPlayback = open(sink, backend)
        advanceBy(5)
        playback.start()
        advanceBy(400)
        val player: SizedPlayer = backend.sized.last()
        onFx { player.resize(80, 60) }
        advanceBy(300)
        onFx { player.inner.forceStatus(NativeStatus.STALLED) }
        advanceBy(2_000)
        onFx { player.inner.forceStatus(NativeStatus.PLAYING) }
        advanceBy(300)
        playback.seekTo(500)
        advanceBy(800)
        onFx { player.resize(64, 48) }
        advanceBy(300)
        playback.dispose()
        advanceBy(50)
        // The doc: the array "the engine never writes again" is the one that was published. A snapshot target that is a published array is that write.
        val published: IdentityHashMap<IntArray, Int> = IdentityHashMap()
        var publishedCount = 0
        var frameIndex = 0
        // Replay the order of events: a target is checked against the frames published up to the time of its snapshot.
        val calls: List<NativeCallEntry> = backend.sized.last().inner.log.calls(NativeCall.SNAPSHOT)
        assertEquals(calls.size, player.snapshotTargets.size, "the log and the wrapper disagree on the snapshots taken")
        assertTrue(sink.frames.isNotEmpty(), "no frame was published, so the premise of the test does not hold")
        for ((index: Int, target: IntArray) in player.snapshotTargets.withIndex()) {
            val atMs: Double = calls[index].tMs
            while (frameIndex < sink.frames.size && sink.frames[frameIndex].atNanos / NANOS_PER_MILLI.toDouble() < atMs) {
                published[sink.frames[frameIndex].frame.pixels] = frameIndex
                publishedCount++
                frameIndex++
            }
            assertTrue(!published.containsKey(target), "snapshot $index at $atMs ms was handed the array of published frame ${published[target]}")
        }
        assertTrue(publishedCount > 20, "only $publishedCount frames were published before the last snapshot")
    }

    @Test
    fun theCopyRateNeverExceedsThirtyPerSecondAtAnyPulseRate() = fxModel {
        for (hertz: Int in listOf(24, 30, 60, 120, 144, 240)) {
            val backend: SizedBackend = backend(pulsePeriodNanos = 1_000_000_000.0 / hertz)
            val sink = RecordingSink(host)
            val playback: FxPlayback = open(sink, backend)
            advanceBy(50)
            val first: Long = host.elapsedNanos
            playback.start()
            advanceBy(3_000)
            playback.dispose()
            advanceBy(50)
            val times: List<Long> = sink.frames.map { it.atNanos }.filter { it >= first }
            assertTrue(times.size >= 3_000 * minOf(hertz, 30) / 1_000 * 8 / 10, "$hertz Hz pulse: only ${times.size} frames in 3 s")
            // At most 30 a second: any half-open second holds 30 frames, plus one when the nanosecond rounding of the grid straddles its end.
            for (t: Long in times) {
                val inWindow: Int = times.count { it >= t && it < t + NANOS_PER_SECOND }
                assertTrue(inWindow <= 31, "$hertz Hz pulse: $inWindow frames within one second from $t ns")
            }
            assertEveryFrameIsItsOwnUnwrittenArray(sink.frames)
        }
    }

    @Test
    fun aLowerFrameRateCapIsKeptAfterALongStallAsWell() = fxModel {
        val backend: SizedBackend = backend()
        val sink = RecordingSink(host)
        val playback: FxPlayback = open(sink, backend, maxFrameRate = 5)
        advanceBy(50)
        playback.start()
        advanceBy(1_000)
        val player: SizedPlayer = backend.sized.last()
        onFx { player.inner.forceStatus(NativeStatus.STALLED) }
        advanceBy(3_000)
        val stalledCount: Int = sink.frames.size
        onFx { player.inner.forceStatus(NativeStatus.PLAYING) }
        val resumedAt: Long = host.elapsedNanos
        advanceBy(2_000)
        playback.dispose()
        advanceBy(50)
        val after: List<Long> = sink.frames.drop(stalledCount).map { it.atNanos }
        assertTrue(after.isNotEmpty(), "no frame followed the stall")
        assertTrue(after.first() - resumedAt < 300_000_000L, "the first frame after the stall came ${(after.first() - resumedAt) / NANOS_PER_MILLI} ms after PLAYING")
        for (t: Long in after) {
            val inWindow: Int = after.count { it >= t && it < t + NANOS_PER_SECOND }
            assertTrue(inWindow <= 6, "$inWindow frames within one second from $t ns at a cap of 5")
        }
        assertEveryFrameIsItsOwnUnwrittenArray(sink.frames)
    }

    @Test
    fun aPausedPlayerCopiesNothingAfterTheFirstPictureAndAfterASeekHasSettled() = fxModel {
        val backend: SizedBackend = backend()
        val sink = RecordingSink(host)
        val playback: FxPlayback = open(sink, backend)
        val log: NativeCallLog = backend.sized.last().inner.log
        advanceBy(2_000)
        val afterFirstPicture: Int = log.calls(NativeCall.SNAPSHOT).size
        assertTrue(sink.frames.isNotEmpty(), "the first picture after prepare was never published")
        advanceBy(4_000)
        assertEquals(afterFirstPicture, log.calls(NativeCall.SNAPSHOT).size, "a paused player kept copying: ${log.tail(4)}")
        playback.seekTo(1_500)
        advanceBy(1_500)
        val afterSeek: Int = log.calls(NativeCall.SNAPSHOT).size
        assertTrue(afterSeek > afterFirstPicture, "a seek while paused produced no copy of the new picture")
        advanceBy(4_000)
        assertEquals(afterSeek, log.calls(NativeCall.SNAPSHOT).size, "the pulse kept copying after the seek had settled")
        assertEquals(false, backend.sized.last().inner.pulsing, "the pulse was still running on a settled paused player")
        playback.dispose()
        advanceBy(50)
    }

    @Test
    fun theFrameOfAFullHdVideoHoldsExactlyWidthTimesHeightPixelsAndNoneIsKept() = fxModel {
        val backend: SizedBackend = backend(clip = ClipModel(width = 1_920, height = 1_080))
        val published: MutableList<Int> = CopyOnWriteArrayList()
        val identities: IdentityHashMap<IntArray, Boolean> = IdentityHashMap()
        val sink = object : FxPlayback.Sink {
            override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) = Unit
            override fun onSeekApplied() = Unit
            override fun onVideoSize(size: VideoSize?) = Unit
            override fun onBuffering(isBuffering: Boolean) = Unit
            override fun onCompleted() = Unit
            override fun onFailed(cause: Throwable) = Unit
            override fun onFrame(frame: VideoFrame) {
                published += frame.pixels.size
                assertTrue(identities.put(frame.pixels, true) == null, "an array was published twice")
                // Keep the identity of the array alive only as long as it takes to compare: the map holds the arrays, so drop the oldest.
                if (identities.size > 3) identities.remove(identities.keys.first())
            }
        }
        val playback: FxPlayback = openWith(sink, backend)
        advanceBy(50)
        playback.start()
        advanceBy(1_000)
        playback.dispose()
        advanceBy(50)
        assertTrue(published.size >= 20, "only ${published.size} frames in one second of 1080p")
        assertTrue(published.all { it == 1_920 * 1_080 }, "a frame was not exactly 1920 x 1080 pixels (8294400 bytes): ${published.distinct()}")
    }

    private fun FxModelScope.openWith(sink: FxPlayback.Sink, backend: SizedBackend): FxPlayback {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        return runBlocking {
            val opening = async { FxPlayback.open("scripted:${TestClip.ASSET}", sink, 30, 15_000L, backend, onCreated = {}) }
            while (!opening.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of an open whose IO hop is real
                if (!opening.isCompleted && System.nanoTime() - deadline > 0L) {
                    opening.cancel()
                    throw AssertionError("open did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            opening.await()
        }
    }

    @Test
    fun aPictureThatDisappearsAndComesBackMidPlayStopsAndRestartsTheCopying() = fxModel {
        val backend: SizedBackend = backend()
        val sink = RecordingSink(host)
        val playback: FxPlayback = open(sink, backend)
        advanceBy(50)
        playback.start()
        advanceBy(500)
        val player: SizedPlayer = backend.sized.last()
        val log: NativeCallLog = player.inner.log
        onFx { player.resize(0, 0) }
        advanceBy(500)
        val whileGone: Int = log.calls(NativeCall.SNAPSHOT).size
        advanceBy(1_000)
        assertEquals(whileGone, log.calls(NativeCall.SNAPSHOT).size, "snapshots were taken of a source that reports no picture")
        assertEquals(false, player.inner.pulsing, "the pulse kept running for a source without a picture")
        assertEquals(null, sink.sizes.last(), "the vanished picture was not reported as no size")
        val framesBefore: Int = sink.frames.size
        onFx { player.resize(64, 48) }
        advanceBy(700)
        assertTrue(sink.frames.size - framesBefore >= 10, "the picture that appeared later got ${sink.frames.size - framesBefore} frames in 700 ms")
        assertEveryFrameIsItsOwnUnwrittenArray(sink.frames)
        playback.dispose()
        advanceBy(50)
    }

    @Test
    fun anAudioOnlySourceAndAVideoSourceInTurnsOnOneEngineShowAPictureOnlyForTheVideo() = fxModel {
        val backend: SizedBackend = backend(initial = { uri: String -> if (uri.endsWith(".m4a")) 0 to 0 else null })
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.AUDIO_ASSET))
        engine.start()
        advanceBy(1_000)
        assertEquals(null, engine.frames.value, "an audio-only source published a frame")
        assertEquals(0, backend.sized.last().inner.log.calls(NativeCall.SNAPSHOT).size, "an audio-only source was snapshotted")
        load(engine, VideoSource.Asset(TestClip.ASSET))
        engine.start()
        advanceBy(1_000)
        val videoFrame: VideoFrame? = engine.frames.value
        assertTrue(videoFrame != null && videoFrame.width == 64 && videoFrame.height == 48, "the video source published $videoFrame")
        load(engine, VideoSource.Asset(TestClip.AUDIO_ASSET))
        assertEquals(null, engine.frames.value, "the last frame of the video was still published after an audio-only source replaced it")
        assertEquals(1, backend.sized[1].inner.log.calls(NativeCall.DISPOSE).size, "the replaced video player was not disposed exactly once")
        engine.start()
        advanceBy(1_000)
        assertEquals(null, engine.frames.value, "the second audio-only source published a frame")
        assertEquals(0, backend.sized[2].inner.log.calls(NativeCall.SNAPSHOT).size, "the second audio-only source was snapshotted")
        load(engine, VideoSource.Asset(TestClip.ASSET))
        engine.start()
        advanceBy(1_000)
        val again: VideoFrame? = engine.frames.value
        assertTrue(again != null && again !== videoFrame, "the second video source published $again")
        engine.release()
        advanceBy(50)
        assertEquals(null, engine.frames.value)
    }

    private companion object {
        const val BOUND_MS: Long = 15_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val NANOS_PER_SECOND: Long = 1_000_000_000L
        const val OPAQUE: Int = 0xFF000000.toInt()
        const val STAMP_MASK: Int = 0x00FFFFFF
    }
}
