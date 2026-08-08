package io.github.jamal_wia.kmptoolkit.haptics

/**
 * The semantic intensity of a haptic event — *what happened*, not how long the motor should run.
 *
 * The three impact types ([LIGHT], [MEDIUM], [HEAVY]) describe a physical bump: a selection
 * changing, a control snapping into place, a drag ending. The three notification types
 * ([SUCCESS], [WARNING], [ERROR]) describe an outcome and are deliberately multi-pulse, so a user
 * can tell them apart without looking at the screen.
 *
 * Every type is supported on every platform this module targets; there is no type that silently
 * has no mapping somewhere. What differs is the *rendering* — see
 * `docs/kmptoolkit-haptics/05-platform-notes.md` for the exact per-platform mapping, including the
 * durations and amplitudes used on Android.
 */
public enum class HapticType {

    /** The lightest tap. Selection changes, ticking through a picker. */
    LIGHT,

    /** A moderate tap. A control committing, a toggle flipping. */
    MEDIUM,

    /** The strongest single tap. A drag snapping home, a heavy-weight action landing. */
    HEAVY,

    /** An operation completed. Rendered as a short two-pulse pattern. */
    SUCCESS,

    /** An operation completed, but something needs attention. Two even pulses. */
    WARNING,

    /** An operation failed. The longest pattern — three pulses. */
    ERROR,
}
