package io.github.jamal_wia.kmptoolkit.haptics

import android.content.Context

/**
 * Creates the Android [HapticFeedback], backed by the device's default vibrator, with every vibration
 * attributed as [HapticAttribution.TOUCH].
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
    createHapticFeedback(context, HapticAttribution.TOUCH)

/**
 * Creates the Android [HapticFeedback], backed by the device's default vibrator, with every vibration
 * carrying [attribution].
 *
 * Everything said on the single-argument overload holds here too; the only difference is how the
 * user's system settings treat the vibrations this instance plays — see [HapticAttribution]. This
 * is a separate overload rather than a default parameter so that code compiled against the
 * single-argument function keeps linking.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param attribution what the vibrations are declared to be for.
 */
public fun createHapticFeedback(context: Context, attribution: HapticAttribution): HapticFeedback =
    AndroidHapticFeedback(
        SystemVibratorPort(
            vibrator = SystemVibratorPort.resolveVibrator(context.applicationContext),
            attribution = attribution,
        ),
    )

/**
 * Maps a [HapticType] onto a platform vibration and reports what the platform made of it.
 *
 * The hardware check comes first so that a device with no motor is reported as
 * [HapticResult.UNAVAILABLE] rather than as a successful no-op — `Vibrator.vibrate` on such a
 * device returns quietly, which would otherwise be indistinguishable from a real pulse. Everything
 * after that is the port's verdict, unmodified: translating framework failures is its job, not
 * this class's.
 */
internal class AndroidHapticFeedback(private val port: VibratorPort) : HapticFeedback {

    override val isAvailable: Boolean get() = port.hasVibrator()

    override fun perform(type: HapticType): HapticResult =
        if (port.hasVibrator()) port.emit(type.toVibration()) else HapticResult.UNAVAILABLE
}
