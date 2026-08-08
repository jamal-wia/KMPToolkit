package io.github.jamal_wia.kmptoolkit.haptics

/**
 * The Android-shaped description of one [HapticType]: either a single pulse or a timing pattern.
 *
 * Kept separate from the code that talks to `android.os.Vibrator` so that the semantic mapping
 * ("what does ERROR feel like") and the API-level plumbing ("how do I play that on API 24 vs 26")
 * can be read — and tested — apart from each other.
 */
internal sealed interface AndroidVibration {

    /** A single pulse of [durationMs], at [amplitude] where the API level supports amplitudes. */
    data class OneShot(val durationMs: Long, val amplitude: Int) : AndroidVibration

    /**
     * An alternating off/on pattern, starting with an off segment, exactly as
     * `VibrationEffect.createWaveform` and the legacy `Vibrator.vibrate(long[], int)` both expect.
     */
    data class Waveform(val timings: List<Long>) : AndroidVibration

    companion object {
        /** Mirrors `VibrationEffect.DEFAULT_AMPLITUDE`, which only exists from API 26. */
        const val DEFAULT_AMPLITUDE: Int = -1
    }
}

/**
 * The single place that decides how each semantic [HapticType] feels on Android.
 *
 * Impacts are one-shots of growing length; notifications are patterns, so `SUCCESS`, `WARNING` and
 * `ERROR` stay distinguishable by feel alone. `LIGHT` is the only type that asks for a reduced
 * amplitude — the others use the device default, which the platform scales by the user's
 * touch-feedback intensity setting, because [SystemVibratorPort] attributes every request as touch
 * feedback.
 */
internal fun HapticType.toVibration(): AndroidVibration = when (this) {
    HapticType.LIGHT -> AndroidVibration.OneShot(LIGHT_MS, HALF_AMPLITUDE)
    HapticType.MEDIUM -> AndroidVibration.OneShot(MEDIUM_MS, AndroidVibration.DEFAULT_AMPLITUDE)
    HapticType.HEAVY -> AndroidVibration.OneShot(HEAVY_MS, AndroidVibration.DEFAULT_AMPLITUDE)
    HapticType.SUCCESS -> AndroidVibration.Waveform(SUCCESS_PATTERN)
    HapticType.WARNING -> AndroidVibration.Waveform(WARNING_PATTERN)
    HapticType.ERROR -> AndroidVibration.Waveform(ERROR_PATTERN)
}

private const val LIGHT_MS: Long = 20L
private const val MEDIUM_MS: Long = 40L
private const val HEAVY_MS: Long = 60L
private const val HALF_AMPLITUDE: Int = 128

// off, on, off, on — a short double tap.
private val SUCCESS_PATTERN: List<Long> = listOf(0L, 20L, 60L, 40L)
private val WARNING_PATTERN: List<Long> = listOf(0L, 40L, 80L, 40L)
private val ERROR_PATTERN: List<Long> = listOf(0L, 60L, 80L, 60L, 80L, 60L)
