package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 round 14, killers for three decision points of `FxPlayback` that no earlier test pinned (mutants C, D and E of the
 * round 14 table). Virtual time, fixed instants.
 *
 * - D: a nudge queued by `start()` runs two FX tasks later; a `release()` in between disposes the player first, and the nudge
 *   must then write nothing on the disposed player (the `disposed` check of `giveNudge`).
 * - E: a speed equal to the one the property holds, given inside the 300 ms seek window of a playing player, is nothing to
 *   defer; a later speed must not queue behind a deferral that was never needed (the `rate != native.rate` test of the
 *   seek-window branch of `setSpeed`).
 * - C: a plain resume whose delayed play follows a seek made during its wait is not a fresh play (no stamp), so a speed given
 *   after its held speed was consumed is written at once and not re-routed through the settle count of a fresh play.
 */
class SurvivorKillersR14ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
    }

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / NANOS_PER_MS

    private fun FxModelScope.rig(body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(
            ScriptedBackend(host, clip = ClipModel(durationMs = 60_000.0), script = StatusScript()),
            timeScript = TimeScript(),
        )
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    @Test
    fun aNudgeQueuedByStartWritesNothingOnAPlayerThatWasReleasedBeforeItRan() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            engine.release()
            advanceBy(200)
            val entries: List<NativeCallEntry> = native.log.entries
            val disposeAt: Int = entries.indexOfFirst { it.call == NativeCall.DISPOSE }
            assertTrue(disposeAt >= 0, "release() disposed the player\n${native.log.tail()}")
            val late: List<NativeCallEntry> = entries.drop(disposeAt + 1).filter { it.call == NativeCall.SET_RATE || it.call == NativeCall.PLAY || it.call == NativeCall.SEEK }
            assertEquals(emptyList(), late, "native calls after dispose()\n${native.log.tail()}")
        }
    }

    @Test
    fun aSpeedGivenAfterTheSeekWindowIsWrittenAtOnceWhenTheSpeedGivenInsideItWasTheOneThePropertyHolds() = fxModel {
        rig {
            engine.setSpeed(2f)
            engine.start()
            advanceBy(1_500)
            engine.seekTo(8_000L)
            advanceBy(10)
            engine.setSpeed(2f)
            // 330 ms after the seek: just past its 300 ms window, while a deferral made inside it would still be counting.
            advanceBy(320)
            val callAt: Double = nowMs()
            engine.setSpeed(3f)
            advanceBy(5)
            val writes: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == 3.0 && it.tMs >= callAt - EPSILON_MS }
            assertEquals(1, writes.size, "the speed was written once\n${native.log.tail()}")
            assertTrue(writes.single().tMs - callAt <= 5.0, "the speed was written ${writes.single().tMs - callAt} ms after the call\n${native.log.tail()}")
        }
    }

    @Test
    fun aSpeedGivenLongAfterADelayedPlainPlayThatFollowedASeekDuringItsWaitIsWrittenAtOnce() = fxModel {
        rig {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(500)
            // Two writes on a paused player: the property ends at 1.0 and the idle gap is open.
            engine.setSpeed(2f)
            advanceBy(1)
            engine.setSpeed(1f)
            advanceBy(1)
            engine.start()
            advanceBy(30)
            engine.seekTo(5_000L)
            // The delayed play comes at the end of the 200 ms gap; the held speed of 1 is consumed after 200 ms of movement.
            advanceBy(700)
            val play: NativeCallEntry = native.log.calls(NativeCall.PLAY).last()
            assertTrue(nowMs() - play.tMs >= 400.0, "the case waits long enough after the play: ${nowMs() - play.tMs}\n${native.log.tail()}")
            val callAt: Double = nowMs()
            // The speed is given 400 to 600 ms after the play and long after the seek: a seek that came before the play
            // makes it no fresh play, so nothing holds the speed back.
            engine.setSpeed(3f)
            advanceBy(5)
            val writes: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == 3.0 && it.tMs >= callAt - EPSILON_MS }
            assertEquals(1, writes.size, "the speed was written once\n${native.log.tail()}")
            assertTrue(writes.single().tMs - callAt <= 5.0, "the speed was written ${writes.single().tMs - callAt} ms after the call\n${native.log.tail()}")
        }
    }

    @Test
    fun aStalledStatusDeliveredTwiceReportsBufferingOnceAndTheNextPlayingReportsItsEnd() = fxModel {
        rig {
            val seen: MutableList<Boolean> = mutableListOf()
            engine.setListener(object : VideoPlaybackEngineListener {
                override fun onCompleted() {}
                override fun onFailed(cause: Throwable) {}
                override fun onBufferingChanged(isBuffering: Boolean) { seen += isBuffering }
                override fun onVideoSizeChanged(size: VideoSize?) {}
            })
            engine.start()
            advanceBy(500)
            val events: NativeEvents = checkNotNull(native.events)
            onFx { events.onStatus(NativeStatus.STALLED) }
            onFx { events.onStatus(NativeStatus.STALLED) }
            assertEquals(listOf(true), seen, "one report for two STALLED statuses")
            onFx { events.onStatus(NativeStatus.PLAYING) }
            onFx { events.onStatus(NativeStatus.PLAYING) }
            assertEquals(listOf(true, false), seen, "one report for the end of the stall")
        }
    }

    private companion object {
        const val NANOS_PER_MS: Double = 1_000_000.0
        const val EPSILON_MS: Double = 0.01
    }
}
