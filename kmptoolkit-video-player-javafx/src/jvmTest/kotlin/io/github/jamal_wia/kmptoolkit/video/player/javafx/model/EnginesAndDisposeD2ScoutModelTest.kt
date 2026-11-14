package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 scout (executor ko), angles (e) two engines in one process and (g) the CoreVideo crash guidance, over the real
 * `JavaFxVideoEngine` and `FxPlayback` on the composed, watched scripted player (virtual time, no media). The oracle is the text of:
 *
 * - `05-platform-notes.md` "Known issue: releasing a video player on macOS can crash the JVM": the engine disposes the JavaFX player
 *   "straight away" on `release()`, on `prepare()` with a new source and on a cancelled load, and an app that follows the guidance
 *   ("let a started player run for a moment") must not meet a disposal the engine decides on its own;
 * - `05-platform-notes.md` "Playback speed": "a `setPlaybackSpeed()` immediately followed by `play()` starts up to 200 ms late", which
 *   moves the instant the display link starts away from the instant the app called `play()`;
 * - `kmptoolkit-video-player/03-guide.md` "One engine per player": two players over two engines are independent; an engine given to a
 *   second player "reports only to the player that took it last", and the first player's `release()` "disposes it under the second".
 */
@OptIn(ToolkitInternalApi::class)
class EnginesAndDisposeD2ScoutModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend)

    private fun FxModelScope.rig(clip: ClipModel = ClipModel(), script: StatusScript = StatusScript()): Rig {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip, script = script))
        return Rig(JavaFxVideoEngine(runtime = {}, backend = { backend }), backend)
    }

    private fun disposes(player: ComposedPlayer): Int = player.player.log.calls(NativeCall.DISPOSE).size

    private fun FxModelScope.prepare(player: VideoPlayer, source: VideoSource) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        runBlocking {
            val preparing = async { player.prepare(source) }
            while (!preparing.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                    preparing.cancel()
                    throw AssertionError("prepare did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            preparing.await()
        }
    }

    @Test
    fun theEngineNeverDisposesANativePlayerOnItsOwnThroughALongPlayWithEveryKindOfEvent() = fxModel {
        val rig: Rig = rig()
        load(rig.engine, VideoSource.Asset(TestClip.ASSET))
        val composed: ComposedPlayer = rig.backend.last
        rig.engine.start()
        advanceBy(600)
        rig.engine.seekTo(1_000)
        advanceBy(400)
        rig.engine.setSpeed(2f)
        advanceBy(800)
        rig.engine.pause()
        advanceBy(600)
        rig.engine.start()
        advanceBy(800)
        rig.engine.setLooping(true)
        advanceBy(7_000)
        onFx { composed.player.forceStatus(NativeStatus.STALLED) }
        advanceBy(1_500)
        onFx { composed.player.forceStatus(NativeStatus.PLAYING) }
        advanceBy(500)
        rig.engine.setLooping(false)
        rig.engine.setSpeed(1f)
        advanceBy(6_000)
        rig.engine.start()
        advanceBy(4_000)
        assertEquals(0, disposes(composed), "the engine disposed the native player without a release or a new source: ${composed.player.log.tail(6)}")
        assertEquals(1, rig.backend.players.size, "the engine built another player on its own")
        rig.engine.release()
        advanceBy(0)
        assertEquals(1, disposes(composed), "release() did not dispose the native player exactly once")
        rig.engine.release()
        advanceBy(50)
        assertEquals(1, disposes(composed), "a second release() disposed again")
    }

    @Test
    fun aNewSourceDisposesTheOldNativePlayerBeforeTheNewOneIsBuilt() = fxModel {
        val rig: Rig = rig()
        load(rig.engine, VideoSource.Asset(TestClip.ASSET))
        rig.engine.start()
        advanceBy(1_000)
        load(rig.engine, VideoSource.Asset(TestClip.ASSET))
        advanceBy(0)
        val old: ComposedPlayer = rig.backend.players[0]
        val replacement: ComposedPlayer = rig.backend.players[1]
        assertEquals(1, disposes(old), "the replaced player was not disposed exactly once")
        assertEquals(0, disposes(replacement))
        val disposedAt: Double = old.player.log.calls(NativeCall.DISPOSE).single().tMs
        val builtAt: Double = replacement.player.log.entries.first().tMs
        assertTrue(disposedAt <= builtAt, "the new native player was built at $builtAt ms, before the old one was disposed at $disposedAt ms")
        // Nothing of the replaced player reached the listener or the frame flow after the new source was loaded.
        assertEquals(0, old.player.log.entries.count { it.tMs > disposedAt && it.effective && it.call != NativeCall.DISPOSE }, "the replaced player was still driven after its disposal: ${old.player.log.tail(5)}")
        rig.engine.release()
        advanceBy(50)
    }

    @Test
    fun aReleaseWhileTheSourceIsStillLoadingDisposesTheHalfBuiltPlayerOnceAndNeverPlaysIt() = fxModel {
        val rig: Rig = rig(script = StatusScript().apply { readyLatencyMs = 5_000.0 })
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        val outcome: Throwable? = runBlocking {
            val loading = async { runCatching { rig.engine.load(VideoSource.Asset(TestClip.ASSET)) }.exceptionOrNull() }
            while (rig.backend.players.isEmpty()) {
                host.advanceBy(1)
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
                check(System.nanoTime() - deadline <= 0L) { "the player was never built ($host)" }
                Thread.onSpinWait()
            }
            host.advanceBy(300)
            rig.engine.release()
            while (!loading.isCompleted) {
                host.advanceBy(1)
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
                check(System.nanoTime() - deadline <= 0L) { "the load did not end after release ($host)" }
                Thread.onSpinWait()
            }
            loading.await()
        }
        advanceBy(100)
        assertTrue(outcome is VideoPlayerReleasedException, "a load released meanwhile ended with $outcome")
        val half: ComposedPlayer = rig.backend.last
        assertEquals(1, disposes(half), "the half-built player was not disposed exactly once: ${half.player.log.tail(6)}")
        assertEquals(0, half.player.log.calls(NativeCall.PLAY).size, "a released half-built player was played")
        assertNull(rig.engine.frames.value)
    }

    @Test
    fun theNativePlayComesNoLaterThanTwoHundredFiftyMillisecondsAfterTheStartAndTheDisposalOfALaterReleaseFollowsIt() = fxModel {
        for ((name: String, prepareThenStart: (Rig) -> Unit) in listOf<Pair<String, (Rig) -> Unit>>(
            "plain start" to { rig: Rig -> rig.engine.start() },
            "speed written while paused" to { rig: Rig -> rig.engine.setSpeed(2f); rig.engine.start() },
            "seek then start" to { rig: Rig -> rig.engine.seekTo(1_200); rig.engine.start() },
        )) {
            val rig: Rig = rig()
            load(rig.engine, VideoSource.Asset(TestClip.ASSET))
            advanceBy(300)
            val startedAt: Double = host.elapsedNanos / NANOS_PER_MILLI.toDouble()
            prepareThenStart(rig)
            advanceBy(500)
            val play: NativeCallEntry = rig.backend.last.player.log.calls(NativeCall.PLAY).first()
            assertTrue(play.tMs - startedAt <= 250.0, "$name: the native play() came ${play.tMs - startedAt} ms after the app's start()")
            val releaseAt: Double = host.elapsedNanos / NANOS_PER_MILLI.toDouble()
            rig.engine.release()
            advanceBy(0)
            val dispose: NativeCallEntry = rig.backend.last.player.log.calls(NativeCall.DISPOSE).single()
            // An app that waits g ms after start() before releasing leaves the display link at least g minus the documented delay.
            assertTrue(dispose.tMs - play.tMs >= (releaseAt - startedAt) - 250.0, "$name: disposed ${dispose.tMs - play.tMs} ms after the native play() for a release ${releaseAt - startedAt} ms after start()")
            assertTrue(dispose.tMs >= play.tMs, "$name: the native player was disposed before the play() the app asked for was issued")
        }
    }

    @Test
    fun releasingOneOfTwoEnginesLeavesTheOtherPlayingWithItsPictureAndItsOwnNativePlayer() = fxModel {
        val first: Rig = rig()
        val second: Rig = rig()
        load(first.engine, VideoSource.Asset(TestClip.ASSET))
        load(second.engine, VideoSource.Asset(TestClip.ASSET))
        first.engine.start()
        second.engine.start()
        advanceBy(800)
        val before: Long = second.engine.positionMs()
        assertNotNull(second.engine.frames.value, "the second engine published no picture, so the premise does not hold")
        first.engine.release()
        advanceBy(0)
        assertEquals(1, disposes(first.backend.last))
        assertEquals(0, disposes(second.backend.last), "releasing one engine disposed the other engine's native player")
        assertNull(first.engine.frames.value, "the released engine still shows its last picture")
        val framesAtRelease: Any? = second.engine.frames.value
        advanceBy(800)
        assertTrue(second.engine.positionMs() > before, "the second engine's playhead stood still after the first was released: $before -> ${second.engine.positionMs()}")
        assertTrue(second.engine.frames.value !== framesAtRelease, "the second engine stopped publishing pictures")
        second.engine.pause()
        advanceBy(300)
        second.engine.seekTo(500)
        second.engine.start()
        advanceBy(600)
        assertTrue(second.backend.last.player.log.calls(NativeCall.PLAY).isNotEmpty())
        assertNull(first.engine.frames.value)
        second.engine.release()
        advanceBy(50)
        assertEquals(1, disposes(second.backend.last))
    }

    @Test
    fun anEngineGivenToASecondPlayerReportsOnlyToTheLastOneAndTheFirstReleaseDisposesItUnderTheSecond() = fxModel {
        val rig: Rig = rig()
        val config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS)
        val firstPlayer: VideoPlayer = videoPlayer(rig.engine, config)
        val secondPlayer: VideoPlayer = videoPlayer(rig.engine, config)
        prepare(secondPlayer, VideoSource.Asset(TestClip.ASSET))
        advanceBy(0)
        assertTrue(secondPlayer.stateFlow.value is VideoPlayerState.Ready, "the player that took the engine last got ${secondPlayer.stateFlow.value}")
        assertTrue(firstPlayer.stateFlow.value is VideoPlayerState.Idle, "the first player was told about a source it never prepared: ${firstPlayer.stateFlow.value}")
        secondPlayer.play()
        advanceBy(500)
        assertTrue(rig.backend.last.player.log.calls(NativeCall.PLAY).isNotEmpty(), "the second player's play() never reached the native player")
        firstPlayer.release()
        advanceBy(0)
        assertEquals(1, disposes(rig.backend.last), "the first player's release() did not dispose the shared engine's native player once")
        // The second player "stops responding": no crash, no further native call on the disposed player, and its own release still works.
        val callsAfterDispose: Int = rig.backend.last.player.log.size
        secondPlayer.pause()
        secondPlayer.play()
        advanceBy(500)
        assertEquals(callsAfterDispose, rig.backend.last.player.log.size, "the second player reached the disposed native player: ${rig.backend.last.player.log.tail(5)}")
        secondPlayer.release()
        advanceBy(50)
        assertEquals(1, disposes(rig.backend.last))
    }

    @Test
    fun aRepeatModeAndASpeedChangeNeverBuildANativePlayerBeyondTheOnesTheAppAskedFor() = fxModel {
        val rig: Rig = rig()
        val player: VideoPlayer = videoPlayer(rig.engine, VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS))
        prepare(player, VideoSource.Asset(TestClip.ASSET))
        advanceBy(0)
        player.setRepeatMode(RepeatMode.One)
        player.play()
        advanceBy(5_000)
        player.setPlaybackSpeed(2f)
        advanceBy(5_000)
        player.pause()
        advanceBy(500)
        player.setRepeatMode(RepeatMode.Off)
        player.play()
        advanceBy(5_000)
        assertEquals(1, rig.backend.players.size, "the player built another native player on its own")
        assertEquals(0, disposes(rig.backend.last), "the native player was disposed while the app only played")
        player.release()
        advanceBy(50)
        assertEquals(1, disposes(rig.backend.last))
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 15_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
