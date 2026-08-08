package io.github.jamal_wia.kmptoolkit.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The narrow slice of `android.os.Vibrator` this module uses.
 *
 * It exists so the things that are hard to observe through the real framework class — a missing
 * `VIBRATE` permission, an absent motor, a service that refuses the request — can be exercised by
 * tests with a hand-written stand-in. Everything above this interface is then plain Kotlin.
 */
internal interface VibratorPort {

    /** Whether the device has a vibration motor at all. */
    fun hasVibrator(): Boolean

    /**
     * Plays [vibration] and reports what the platform made of it.
     *
     * Returns exactly the [HapticResult] the caller should see: [HapticResult.PERFORMED] when the
     * framework accepted the request, [HapticResult.PERMISSION_DENIED] when the app has not
     * declared `android.permission.VIBRATE`, [HapticResult.FAILED] when the vibrator service
     * refused or was unreachable, and [HapticResult.UNAVAILABLE] when there is no vibrator to talk
     * to at all. Never throws — translating the framework's exceptions is this seam's whole job.
     */
    fun emit(vibration: AndroidVibration): HapticResult
}

/**
 * The real [VibratorPort], on top of the platform [Vibrator].
 *
 * Three API levels matter here, and each is branched on `Build.VERSION.SDK_INT` directly so that
 * lint can see the guard:
 * - **API 33 (`TIRAMISU`)** — `VibrationAttributes` replaces `AudioAttributes` as the way to say
 *   what a vibration is *for*.
 * - **API 31 (`S`)** — `VIBRATOR_SERVICE` is deprecated in favour of `VibratorManager`, whose
 *   `defaultVibrator` is the one the user perceives as "the" motor.
 * - **API 26 (`O`)** — `VibrationEffect` appears, and with it amplitude control. Below it, only
 *   the deprecated duration-and-pattern overloads exist, and amplitude is not expressible.
 *
 * `minSdk` for this library is 24, so the pre-`VibrationEffect` branch is not dead code.
 *
 * **Every request is attributed as touch feedback.** An unattributed `vibrate()` is classified
 * `USAGE_UNKNOWN`, which the platform treats as an unclassified vibration: it is not scaled by the
 * user's touch-feedback intensity slider, not silenced by the touch-feedback switch, and is
 * filtered differently under Do Not Disturb. Attribution is what makes "the user's settings are
 * respected" true rather than aspirational.
 */
internal class SystemVibratorPort(private val vibrator: Vibrator?) : VibratorPort {

    // Cheap and immutable, so it is built once. Its API-33+ counterpart cannot be: VibrationAttributes
    // did not exist before then, and hoisting it into a field would need an annotation (and a
    // dependency on androidx.annotation) to tell lint what the version guard already says.
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun hasVibrator(): Boolean = vibrator?.hasVibrator() == true

    // MissingPermission is suppressed, not fixed, and this is the one place in the module where
    // that is the right call: this library deliberately declares no android.permission.VIBRATE
    // (docs/01-architecture.md#android-manifests), so lint is reporting exactly the situation the
    // design intends. The SecurityException the consumer would get without the permission is
    // caught right here and reported as PERMISSION_DENIED. Annotating with @RequiresPermission
    // instead would push the same error onto every consumer's call site, which is noise rather
    // than information — the requirement is documented in 02-getting-started.md and
    // 05-platform-notes.md.
    @Suppress("MissingPermission")
    override fun emit(vibration: AndroidVibration): HapticResult {
        val target: Vibrator = vibrator ?: return HapticResult.UNAVAILABLE
        return try {
            // The VibrationEffect construction is inline rather than extracted: lint's API-level
            // guard analysis is lexical, so moving it into a helper would need an annotation to
            // say what this `if` already says.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect: VibrationEffect = when (vibration) {
                    is AndroidVibration.OneShot ->
                        VibrationEffect.createOneShot(vibration.durationMs, vibration.amplitude)
                    is AndroidVibration.Waveform ->
                        VibrationEffect.createWaveform(vibration.timings.toLongArray(), NO_REPEAT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attributes: VibrationAttributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()
                    target.vibrate(effect, attributes)
                } else {
                    target.vibrate(effect, audioAttributes)
                }
            } else {
                @Suppress("DEPRECATION")
                when (vibration) {
                    is AndroidVibration.OneShot ->
                        target.vibrate(vibration.durationMs, audioAttributes)
                    is AndroidVibration.Waveform ->
                        target.vibrate(vibration.timings.toLongArray(), NO_REPEAT, audioAttributes)
                }
            }
            HapticResult.PERFORMED
        } catch (_: SecurityException) {
            HapticResult.PERMISSION_DENIED
        } catch (_: RuntimeException) {
            // The framework throws more than SecurityException: IllegalArgumentException for an
            // effect it will not accept, and OEM builds wrap a dead vibrator service binder in a
            // RuntimeException. None of that is worth crashing a click handler over, and none of
            // it is a standing property of the device — hence FAILED rather than UNAVAILABLE.
            // Error is deliberately not caught: an OOM or a linkage failure is not ours to hide.
            HapticResult.FAILED
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
