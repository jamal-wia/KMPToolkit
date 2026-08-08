package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivitySubscription

/**
 * Creates the Android [SystemBarsController].
 *
 * @param activityAccess how the controller reaches the window it styles. It takes an
 *   [ActivityAccess] rather than an `Activity` on purpose: the bars belong to whichever activity is
 *   resumed *now*, and that identity changes on every rotation, theme change and font-size change.
 *   `kmptoolkit-platform` already solves that with a weakly-held, lifecycle-driven tracker, and a
 *   second one here would be a second thing to keep in sync. Create it once with
 *   `createActivityTracker(application)` and share it.
 * @param initialConfig the base configuration to start from, before your theme sets one.
 * @return a controller whose lifetime is yours. Call [SystemBarsController.release] if you tear the
 *   graph down without ending the process.
 */
public fun createSystemBarsController(
    activityAccess: ActivityAccess,
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController = AndroidSystemBarsController(activityAccess, initialConfig)

/**
 * Applies the configuration through `WindowInsetsControllerCompat`, which is the one API that
 * covers both icon appearance and bar visibility on every level from minSdk up.
 *
 * It deliberately does **not** call `enableEdgeToEdge()`. Going edge-to-edge changes how the app's
 * own layout is measured and which insets it has to consume, and that is an app-wide decision a
 * library has no business making from inside a "style the bars" call — it is one line in the
 * consumer's activity. See `docs/kmptoolkit-systembars/05-platform-notes.md`.
 */
private class AndroidSystemBarsController(
    private val activityAccess: ActivityAccess,
    initialConfig: SystemBarsConfig,
) : LayeredSystemBarsController(initialConfig) {

    /**
     * Re-applies on every resume, including the immediate one at construction.
     *
     * A recreated activity is a brand-new window at platform defaults while this controller still
     * holds the state the previous one had — nothing changed as far as the state is concerned, so
     * only an unconditional re-apply fixes it.
     */
    private val subscription: ActivitySubscription =
        activityAccess.addOnActivityResumedListener { reapplyToPlatform() }

    override fun applyToPlatform(config: SystemBarsConfig) {
        activityAccess.withActivity { activity ->
            // Window and decor-view mutations are main-thread only, and a caller is allowed to be
            // anywhere. runOnUiThread runs inline when already there, so the common case costs
            // nothing.
            activity.runOnUiThread { apply(activity, config) }
        }
    }

    override fun release() {
        subscription.cancel()
        super.release()
    }

    private fun apply(activity: Activity, config: SystemBarsConfig) {
        val insetsController: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        insetsController.isAppearanceLightStatusBars =
            config.statusBarIcons == SystemBarIconStyle.DarkIcons
        insetsController.isAppearanceLightNavigationBars =
            config.navigationBarIcons == SystemBarIconStyle.DarkIcons

        insetsController.systemBarsBehavior = when (config.visibility.hiddenBarBehavior) {
            HiddenBarBehavior.SwipeToReveal ->
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            HiddenBarBehavior.StayHidden ->
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        applyBar(insetsController, WindowInsetsCompat.Type.statusBars(), config.visibility.isStatusBarVisible)
        applyBar(
            insetsController,
            WindowInsetsCompat.Type.navigationBars(),
            config.visibility.isNavigationBarVisible,
        )
    }

    private fun applyBar(controller: WindowInsetsControllerCompat, type: Int, visible: Boolean) {
        if (visible) controller.show(type) else controller.hide(type)
    }
}

@Composable
internal actual fun applyDialogWindowSystemBars(config: SystemBarsConfig) {
    val view: android.view.View = LocalView.current
    LaunchedEffect(view, config.statusBarIcons, config.navigationBarIcons) {
        // A Dialog/Popup/ModalBottomSheet renders into a child window whose root view's parent
        // implements DialogWindowProvider. Called from ordinary activity content there is no such
        // parent, and nothing needs doing — the activity's own window is already styled.
        val window: android.view.Window =
            (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        val insetsController: WindowInsetsControllerCompat = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars =
            config.statusBarIcons == SystemBarIconStyle.DarkIcons
        insetsController.isAppearanceLightNavigationBars =
            config.navigationBarIcons == SystemBarIconStyle.DarkIcons
    }
}
