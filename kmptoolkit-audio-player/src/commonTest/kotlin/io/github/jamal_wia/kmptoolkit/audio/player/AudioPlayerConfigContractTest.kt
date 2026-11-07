package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The configuration is documented as validated where it is written, so that a nonsensical value
 * fails there rather than as silently wrong playback later. A speed that is not a number names no
 * rate at all.
 */
class AudioPlayerConfigContractTest {

    @Test
    fun `a NaN maximum speed is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AudioPlayerConfig(maxPlaybackSpeed = Float.NaN)
        }
    }
}
