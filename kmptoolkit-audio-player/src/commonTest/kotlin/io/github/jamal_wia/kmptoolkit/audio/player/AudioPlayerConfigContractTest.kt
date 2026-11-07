package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The configuration is documented as validated where it is written, so that a nonsensical value
 * fails there rather than as silently wrong playback later. A speed that is not a number names no
 * rate at all.
 */
class AudioPlayerConfigContractTest {

    @Test
    fun `an infinite maximum speed is rejected where it is written`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(maxPlaybackSpeed = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `an infinite minimum speed is rejected where it is written`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(minPlaybackSpeed = Float.POSITIVE_INFINITY, maxPlaybackSpeed = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `the largest finite maximum speed is still accepted`() {
        assertEquals(Float.MAX_VALUE, AudioPlayerConfig(maxPlaybackSpeed = Float.MAX_VALUE).maxPlaybackSpeed)
    }

    @Test
    fun `a NaN maximum speed is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(maxPlaybackSpeed = Float.NaN)
        }
    }
}
