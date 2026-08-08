package io.github.jamal_wia.kmptoolkit.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The narrow slice of `android.os.Vibrator` this module uses.
 *
 * It exists so the two things that are genuinely hard to observe through the real framework class —
 * a missing `VIBRATE` permission, and the absence of a motor — can be exercised by tests with a
 * hand-written stand-in. Everything above this interface is then plain Kotlin.
 */
internal interface VibratorPort {

    /** Whether the device has a vibration motor at all. */
    fun hasVibrator(): Boolean

    /**
     * Plays [vibration].
     *
     * @return `false` when the consuming app has not declared `android.permission.VIBRATE`, which
     *   is the one framework failure with a defined meaning here. Reporting it rather than throwing
     *   keeps `SecurityException` — a framework detail — from leaking above this seam, and lets the
     *   layer above turn it into [HapticResult.PERMISSION_DENIED] without a `catch`.
     */
    fun emit(vibration: AndroidVibration): Boolean
}

/**
 * The real [VibratorPort], on top of the platform [Vibrator].
 *
 * Two API levels matter here, and both are branched on `Build.VERSION.SDK_INT` directly so that
 * lint can see the guard:
 * - **API 31 (S)** — `VIBRATOR_SERVICE` is deprecated in favour of `VibratorManager`, whose
 *   `defaultVibrator` is the one the user perceives as "the" motor.
 * - **API 26 (O)** — `VibrationEffect` appears, and with it amplitude control. Below it, only the
 *   deprecated duration-and-pattern overloads exist, and amplitude is simply not expressible; the
 *   pulse plays at whatever strength the device chooses.
 *
 * `minSdk` for this library is 24, so the pre-`VibrationEffect` branch is not dead code.
 */
internal class SystemVibratorPort(private val vibrator: Vibrator?) : VibratorPort {

    override fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    // MissingPermission is suppressed, not fixed, and this is the one place in the module where
    // that is the right call: this library deliberately declares no android.permission.VIBRATE
    // (docs/01-architecture.md#android-manifests), so lint is reporting exactly the situation the
    // design intends. The SecurityException the consumer would get without the permission is
    // caught right here and reported as `false`, which the layer above turns into
    // HapticResult.PERMISSION_DENIED. Annotating with @RequiresPermission instead would push the
    // same error onto every consumer's call site, which is noise rather than information — the
    // requirement is documented in 02-getting-started.md and 05-platform-notes.md.
    @Suppress("MissingPermission")
    override fun emit(vibration: AndroidVibration): Boolean {
        val target: Vibrator = vibrator ?: return false
        return try {
            // The VibrationEffect construction is inline rather than extracted: lint's API-level
            // guard analysis is lexical, so moving it into a helper would need an annotation (and
            // a dependency on androidx.annotation) to say what this `if` already says.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect: VibrationEffect = when (vibration) {
                    is AndroidVibration.OneShot ->
                        VibrationEffect.createOneShot(vibration.durationMs, vibration.amplitude)
                    is AndroidVibration.Waveform ->
                        VibrationEffect.createWaveform(vibration.timings.toLongArray(), NO_REPEAT)
                }
                target.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                when (vibration) {
                    is AndroidVibration.OneShot -> target.vibrate(vibration.durationMs)
                    is AndroidVibration.Waveform ->
                        target.vibrate(vibration.timings.toLongArray(), NO_REPEAT)
                }
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal companion object {
        /** `-1` as the repeat index means "play the pattern once". */
        const val NO_REPEAT: Int = -1

        /**
         * Resolves the device's default vibrator, or `null` when the system service is missing —
         * which happens on stripped-down builds and some emulators, and is not an error.
         */
        fun resolveVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager: VibratorManager? =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
    }
}
