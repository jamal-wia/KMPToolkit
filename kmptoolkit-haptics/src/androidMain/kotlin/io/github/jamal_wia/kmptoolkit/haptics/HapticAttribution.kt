package io.github.jamal_wia.kmptoolkit.haptics

/**
 * What an Android vibration is declared to be *for* — which is what decides how the user's system
 * settings treat it.
 *
 * Chosen once per instance, in [createHapticFeedback]. An app whose vibrations mean different things
 * builds one instance per meaning.
 */
public enum class HapticAttribution {

    /**
     * The vibration is touch feedback: `VibrationAttributes.USAGE_TOUCH` on API 33+, sonification
     * `AudioAttributes` below. The default, and the right answer for a tap confirming a user's own
     * action.
     *
     * The platform scales it by the user's touch-feedback intensity and silences it when they turn
     * touch feedback off — a user who disabled "vibrate on touch" does not want your taps either.
     */
    TOUCH,

    /**
     * The vibration carries no attribution at all: the plain `vibrate(effect)` overloads, classified
     * by the platform as `USAGE_UNKNOWN`.
     *
     * The touch-feedback switch and slider do not apply to it. Use it for a vibration that is not a
     * reaction to the user's touch and must reach them regardless of that switch — an attention cue
     * aimed at someone who put the device down, say. It is also exactly what an app calling
     * `Vibrator.vibrate` directly gets, so moving such an app onto this module with [NONE] keeps its
     * vibrations behaving as they did.
     */
    NONE,
}
