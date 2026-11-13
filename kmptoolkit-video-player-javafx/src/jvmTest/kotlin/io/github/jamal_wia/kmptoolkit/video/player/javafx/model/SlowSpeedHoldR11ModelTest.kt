package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timing sentence of the 5.0.0 CHANGELOG (Fixed, `kmptoolkit-video-player-javafx`): a speed given to a playing
 * player right after a seek is held back until at least 300 ms after the seek and the playhead has moved on, so it
 * takes effect up to about half a second later at normal speed, "about 0.7 s from 0.5x" and "about 1.1 s from 0.25x".
 * The playhead moves 200 ms of media before the speed is given (the 200 ms settle), which takes 200 / speed of wall
 * time, so the delay is 300 ms + 200 / s0, plus up to two 100 ms ticks of the published playhead. No other class names
 * the slow-speed numbers (r11 Q3: unpinned).
 */
class SlowSpeedHoldR11ModelTest {

    private fun FxModelScope.nowMs(): Double = host.elapsedNanos / 1_000_000.0

    /** Seeks while playing at [heldSpeed], asks for a speed of 2 at once, and returns how many ms after the seek it reached the native player. */
    private fun FxModelScope.delayOfTheSpeedAfterASeek(heldSpeed: Float): Double {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            val native: ScriptedNativePlayer = backend.last.player
            if (heldSpeed != 1f) engine.setSpeed(heldSpeed)
            advanceBy(500)
            engine.start()
            advanceBy(1_200)
            val seekAt: Double = nowMs()
            engine.seekTo(500L)
            engine.setSpeed(2f)
            advanceBy(4_000)
            val given: List<NativeCallEntry> = native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.arg == 2.0 && it.tMs >= seekAt }
            assertEquals(1, given.size, "the speed of 2 reached the native player once\n${native.log}")
            assertFalse(backend.last.hazards.frozen, native.log.tail())
            return given.single().tMs - seekAt
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    private fun check(heldSpeed: Float, documentedMs: Double) = fxModel {
        val delay: Double = delayOfTheSpeedAfterASeek(heldSpeed)
        assertTrue(delay >= documentedMs - 50.0, "at $heldSpeed the speed was given $delay ms after the seek, earlier than the documented about $documentedMs ms")
        assertTrue(delay <= documentedMs + 250.0, "at $heldSpeed the speed was given $delay ms after the seek, later than the documented about $documentedMs ms")
    }

    @Test
    fun aSpeedAfterASeekAtNormalSpeedTakesEffectWithinAboutHalfASecond() = check(1f, 500.0)

    @Test
    fun aSpeedAfterASeekFromHalfSpeedTakesEffectAfterAboutSevenTenthsOfASecond() = check(0.5f, 700.0)

    @Test
    fun aSpeedAfterASeekFromQuarterSpeedTakesEffectAfterAboutElevenTenthsOfASecond() = check(0.25f, 1_100.0)
}
