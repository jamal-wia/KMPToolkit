package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [PlayerState] extension properties a consumer's UI reads directly. They are pure functions of
 * the state, so they are tested case by case rather than through a player.
 */
class PlayerStateTest {

    private val boom: Throwable = IllegalStateException("boom")

    @Test
    fun `idle and preparing are not playable`() {
        assertFalse(PlayerState.Idle.isPlayable)
        assertFalse(PlayerState.Preparing.isPlayable)
    }

    @Test
    fun `every state holding a loaded source is playable`() {
        assertTrue(PlayerState.Ready(duration = 1_000L).isPlayable)
        assertTrue(PlayerState.Playing(duration = 1_000L, currentPosition = 0L).isPlayable)
        assertTrue(PlayerState.Paused(duration = 1_000L, currentPosition = 0L).isPlayable)
        assertTrue(PlayerState.Completed(duration = 1_000L).isPlayable)
    }

    @Test
    fun `an error is not playable`() {
        assertFalse(PlayerState.Error(boom).isPlayable)
    }

    @Test
    fun `only playing reports isPlaying`() {
        assertTrue(PlayerState.Playing(duration = 1_000L, currentPosition = 0L).isPlaying)
        assertFalse(PlayerState.Idle.isPlaying)
        assertFalse(PlayerState.Preparing.isPlaying)
        assertFalse(PlayerState.Ready(duration = 1_000L).isPlaying)
        assertFalse(PlayerState.Paused(duration = 1_000L, currentPosition = 0L).isPlaying)
        assertFalse(PlayerState.Completed(duration = 1_000L).isPlaying)
        assertFalse(PlayerState.Error(boom).isPlaying)
    }

    @Test
    fun `duration is null while no source is loaded`() {
        assertNull(PlayerState.Idle.duration)
        assertNull(PlayerState.Preparing.duration)
        assertNull(PlayerState.Error(boom).duration)
    }

    @Test
    fun `duration is exposed by every loaded state`() {
        assertEquals(1_000L, PlayerState.Ready(duration = 1_000L).duration)
        assertEquals(2_000L, PlayerState.Playing(duration = 2_000L, currentPosition = 500L).duration)
        assertEquals(3_000L, PlayerState.Paused(duration = 3_000L, currentPosition = 500L).duration)
        assertEquals(4_000L, PlayerState.Completed(duration = 4_000L).duration)
    }

    @Test
    fun `playbackPosition is null where there is no meaningful playhead`() {
        assertNull(PlayerState.Idle.playbackPosition)
        assertNull(PlayerState.Preparing.playbackPosition)
        assertNull(PlayerState.Ready(duration = 1_000L).playbackPosition)
        assertNull(PlayerState.Error(boom).playbackPosition)
    }

    @Test
    fun `playbackPosition follows the playhead while playing or paused`() {
        assertEquals(
            500L,
            PlayerState.Playing(duration = 1_000L, currentPosition = 500L).playbackPosition,
        )
        assertEquals(
            250L,
            PlayerState.Paused(duration = 1_000L, currentPosition = 250L).playbackPosition,
        )
    }

    @Test
    fun `a completed playhead sits at the duration`() {
        assertEquals(1_000L, PlayerState.Completed(duration = 1_000L).playbackPosition)
    }

    @Test
    fun `progress is zero without a duration or a position`() {
        assertEquals(0f, PlayerState.Idle.progress)
        assertEquals(0f, PlayerState.Preparing.progress)
        assertEquals(0f, PlayerState.Ready(duration = 1_000L).progress)
        assertEquals(0f, PlayerState.Error(boom).progress)
    }

    @Test
    fun `progress is zero when the duration is zero or negative`() {
        assertEquals(0f, PlayerState.Playing(duration = 0L, currentPosition = 0L).progress)
        assertEquals(0f, PlayerState.Paused(duration = -100L, currentPosition = 0L).progress)
    }

    @Test
    fun `progress is the position over the duration`() {
        assertEquals(0.5f, PlayerState.Playing(duration = 1_000L, currentPosition = 500L).progress)
        assertEquals(0.25f, PlayerState.Paused(duration = 1_000L, currentPosition = 250L).progress)
    }

    @Test
    fun `progress is one when completed`() {
        assertEquals(1f, PlayerState.Completed(duration = 1_000L).progress)
    }

    @Test
    fun `progress clamps a position past the end to one`() {
        assertEquals(1f, PlayerState.Playing(duration = 1_000L, currentPosition = 1_500L).progress)
    }

    @Test
    fun `progress clamps a negative position to zero`() {
        assertEquals(0f, PlayerState.Playing(duration = 1_000L, currentPosition = -100L).progress)
    }

    @Test
    fun `an error carries the cause rather than a message to display`() {
        val state: PlayerState = PlayerState.Error(boom)
        assertEquals(boom, (state as PlayerState.Error).cause)
    }
}
