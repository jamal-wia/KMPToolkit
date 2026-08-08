package io.github.jamal_wia.kmptoolkit.haptics

import android.content.Context

/**
 * Creates the Android [HapticFeedback], backed by the device's default vibrator.
 *
 * Call this once — in your `Application`, or wherever you assemble dependencies — and pass the
 * resulting [HapticFeedback] into shared code. The instance holds only the framework `Vibrator`
 * obtained from [context]; nothing needs releasing, and it does not keep a strong reference to an
 * `Activity` (the application context is used, so passing an `Activity` here is harmless).
 *
 * The app must declare `android.permission.VIBRATE` itself — this library declares no permission,
 * on purpose. Without it every call returns [HapticResult.PERMISSION_DENIED] instead of throwing;
 * see `docs/kmptoolkit-haptics/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 */
public fun createHapticFeedback(context: Context): HapticFeedback =
    AndroidHapticFeedback(
        SystemVibratorPort(SystemVibratorPort.resolveVibrator(context.applicationContext)),
    )

/**
 * Maps a [HapticType] onto a platform vibration and reports what the platform made of it.
 *
 * The hardware check comes first so that a device with no motor is reported as
 * [HapticResult.UNAVAILABLE] rather than as a successful no-op — `Vibrator.vibrate` on such a
 * device returns quietly, which would otherwise be indistinguishable from a real pulse.
 */
internal class AndroidHapticFeedback(private val port: VibratorPort) : HapticFeedback {

    override fun perform(type: HapticType): HapticResult {
        if (!port.hasVibrator()) return HapticResult.UNAVAILABLE
        // A rejected emit means the app forgot android.permission.VIBRATE. A decorative tap must
        // not take down the screen that asked for it, so this is reported, not thrown.
        return if (port.emit(type.toVibration())) {
            HapticResult.PERFORMED
        } else {
            HapticResult.PERMISSION_DENIED
        }
    }
}
