package io.github.jamal_wia.kmptoolkit.audio.player.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `PlaybackEngine` documents a duration of `0` as "unknown", not as "zero length", and `positionMs`
 * as never negative. The fake is meant to behave like the engines it stands in for.
 */
class FakePlaybackEngineUnknownLengthTest {

    @Test
    fun `advancing the playhead in a source of unknown length moves it`() {
        val engine = FakePlaybackEngine(durationMs = 0L)

        engine.advancePositionBy(5_000L)

        assertEquals(5_000L, engine.positionMs(), "an unknown length is not a length of zero")
    }

    @Test
    fun `advancing by a negative amount never produces a negative position`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        engine.advancePositionTo(1_000L)

        engine.advancePositionBy(-5_000L)

        assertTrue(engine.positionMs() >= 0L, "position was ${engine.positionMs()}")
    }

    @Test
    fun `moving the playhead to a negative position never produces a negative position`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)

        engine.advancePositionTo(-1L)

        assertTrue(engine.positionMs() >= 0L, "position was ${engine.positionMs()}")
    }

    @Test
    fun `a negative length is refused where it is configured`() {
        val refused: Boolean = try {
            FakePlaybackEngine(durationMs = -1L).durationMs() >= 0L
        } catch (rejected: IllegalArgumentException) {
            true
        }
        assertTrue(refused, "durationMs() answered a negative length")
    }

    @Test
    fun `the setter refuses a negative length`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)

        assertFailsWith<IllegalArgumentException> { engine.durationMs = -1L }
        assertEquals(10_000L, engine.durationMs)
    }

    @Test
    fun `with a known length the largest amount still lands on the end`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)

        engine.advancePositionBy(Long.MAX_VALUE)

        assertEquals(10_000L, engine.positionMs())
    }
}
