package io.github.jamal_wia.kmptoolkit.platform.accessibility

import android.content.Context
import android.provider.Settings

/**
 * Creates the Android [ReducedMotionProbe], reading `Settings.Global.ANIMATOR_DURATION_SCALE`.
 *
 * That global is what both Accessibility → "Remove animations" and the developer options
 * animation-scale sliders write to; a scale of zero means the user asked for no animation. Android
 * has no separate "reduce motion" flag, so this is the signal.
 *
 * Reading it needs no permission — it is a public global setting. Only the application context is
 * retained, so passing an `Activity` here is harmless.
 *
 * @param context any `Context`; its application context is what gets retained.
 */
public fun createReducedMotionProbe(context: Context): ReducedMotionProbe =
    AndroidReducedMotionProbe(context.applicationContext)

private class AndroidReducedMotionProbe(private val context: Context) : ReducedMotionProbe {

    override fun isReducedMotionEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            DEFAULT_ANIMATOR_DURATION_SCALE,
        ) == 0f
    }.getOrDefault(false)

    private companion object {
        /**
         * The platform default, used when the setting has never been written. `1f` means "normal
         * animation", so an unset value reads as reduced motion being off — failing open, which is
         * what [ReducedMotionProbe.isReducedMotionEnabled] promises.
         */
        const val DEFAULT_ANIMATOR_DURATION_SCALE: Float = 1f
    }
}
