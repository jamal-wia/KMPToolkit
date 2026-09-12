package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess

/**
 * Creates the Android [SystemBarsController].
 *
 * @param context any `Context`; its application context is retained to track the currently
 *   resumed activity, which the controller needs to reach the window it styles — the bars belong
 *   to whichever activity is resumed *now*, and that identity changes on every rotation, theme
 *   change and font-size change.
 * **Call this from `Application.onCreate`, before any activity resumes.** The controller learns which
 * window to style from the activity-resumed callback, and Android offers no way to ask which activity
 * resumed before the callback was registered — so a controller created later, such as a lazy DI
 * singleton first resolved by your theme during composition, cannot style the window already on
 * screen until the next resume. See `docs/kmptoolkit-systembars/05-platform-notes.md`.
 *
 * @param initialConfig the base configuration to start from, before your theme sets one.
 * @return a controller whose lifetime is yours. Call [SystemBarsController.release] if you tear the
 *   graph down without ending the process.
 */
public fun createSystemBarsController(
    context: Context,
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): SystemBarsController = createSystemBarsController(
    activityAccess = createActivityAccess(context.applicationContext as Application),
    initialConfig = initialConfig,
)

/**
 * Creates the Android [SystemBarsController] over an [ActivityAccess] you own.
 *
 * Use this instead of the `Context` overload when "the activity on top" is not the activity you
 * mean. That overload tracks every activity in the process, so anything your app launches into its
 * own process — a sign-in flow, a photo picker, a `ComponentActivity` an SDK declared in its own
 * manifest — gets your bar configuration applied to its window the moment it resumes, including a
 * fullscreen one a screen underneath had claimed. Narrow it with
 * `createActivityAccess(application) { it is MainActivity }`.
 *
 * The [ActivityAccess] is yours: this controller does not release it, so one instance can back
 * several controllers, and [SystemBarsController.release] leaves it registered. It has to exist
 * before the first activity resumes — create it, and this controller, in `Application.onCreate`;
 * see the other overload for why.
 *
 * @param activityAccess where the window to style comes from.
 * @param initialConfig the base configuration to start from, before your theme sets one.
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
     * Re-applies on every resume the tracker sees, and at construction if the tracker already holds
     * a resumed activity — which it does only when it existed before that activity resumed.
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
