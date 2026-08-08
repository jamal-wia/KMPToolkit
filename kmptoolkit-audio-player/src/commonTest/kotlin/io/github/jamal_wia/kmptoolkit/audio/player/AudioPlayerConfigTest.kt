package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [AudioPlayerConfig] rejects values that would produce silently broken playback — a zero polling
 * interval spins a coroutine flat out, a zero speed is a rate both platforms refuse, and an
 * inverted range makes `coerceIn` throw far away from the mistake.
 */
class AudioPlayerConfigTest {

    @Test
    fun `the defaults are the documented ones`() {
        val config = AudioPlayerConfig()

        assertEquals(100L, config.positionUpdateIntervalMs)
        assertEquals(0.25f, config.minPlaybackSpeed)
        assertEquals(3.0f, config.maxPlaybackSpeed)
    }

    @Test
    fun `a zero polling interval is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(positionUpdateIntervalMs = 0L)
        }
    }

    @Test
    fun `a negative polling interval is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(positionUpdateIntervalMs = -1L)
        }
    }

    @Test
    fun `a zero minimum speed is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(minPlaybackSpeed = 0f)
        }
    }

    @Test
    fun `a maximum speed below the minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(minPlaybackSpeed = 2.0f, maxPlaybackSpeed = 1.0f)
        }
    }

    @Test
    fun `a single-speed range is allowed`() {
        val config = AudioPlayerConfig(minPlaybackSpeed = 1.0f, maxPlaybackSpeed = 1.0f)

        assertEquals(1.0f, config.minPlaybackSpeed)
        assertEquals(1.0f, config.maxPlaybackSpeed)
    }
}
