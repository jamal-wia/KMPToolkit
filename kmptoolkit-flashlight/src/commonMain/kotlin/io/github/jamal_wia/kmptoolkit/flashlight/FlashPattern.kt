package io.github.jamal_wia.kmptoolkit.flashlight

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the torch blinks — one on/off rhythm per purpose.
 *
 * Neither pattern holds the torch on continuously: it is a cue, not a lamp, so every cycle has a
 * dark gap.
 *
 * @property on how long the torch stays lit in one cycle.
 * @property off the dark gap before the next cycle.
 */
public enum class FlashPattern(public val on: Duration, public val off: Duration) {

    /** A quick flash to accompany a louder cue — noticed, then over. */
    Attention(on = 120.milliseconds, off = 180.milliseconds),

    /**
     * The blink for a device left face-down: long enough to be seen from across a room and read
     * as deliberate, short enough not to become a lamp.
     */
    Blink(on = 600.milliseconds, off = 400.milliseconds),
}
