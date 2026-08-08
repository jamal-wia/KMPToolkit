package io.github.jamal_wia.kmptoolkit.platform.device

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** Android's own threshold for a "large screen" resource bucket, in density-independent pixels. */
private const val TABLET_SMALLEST_WIDTH_DP: Int = 600

/**
 * Creates the Android [DeviceInfo].
 *
 * Everything except [DeviceInfo.currentCountry] is read once, at construction, from `Build` and
 * the current `Configuration` — those do not change while the process lives. The country is read
 * live on every call, because the user can change the region in Settings without restarting the
 * app.
 *
 * Only the application context is retained, so passing an `Activity` here is harmless. No
 * permission is required for any of it.
 *
 * @param context any `Context`; its application context is what gets retained.
 */
public fun createDeviceInfo(context: Context): DeviceInfo =
    AndroidDeviceInfo(context.applicationContext)

private class AndroidDeviceInfo(context: Context) : DeviceInfo {

    override val osName: String = "Android"

    override val osVersion: String =
        Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: Build.VERSION.SDK_INT.toString()

    override val model: String = composeDeviceModel(Build.MANUFACTURER, Build.MODEL)

    /**
     * `smallestScreenWidthDp` rather than the current width: it is the width of the *shorter*
     * dimension, so it does not change when the device is rotated, which keeps the form factor a
     * property of the hardware instead of a property of how the user is holding it.
     */
    override val formFactor: FormFactor = when {
        context.resources.configuration.smallestScreenWidthDp ==
            Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED -> FormFactor.UNKNOWN

        context.resources.configuration.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP ->
            FormFactor.TABLET

        else -> FormFactor.PHONE
    }

    override fun currentCountry(): String? = normalizeCountryCode(Locale.getDefault().country)
}
