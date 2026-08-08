package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.Composable
import platform.Foundation.NSThread
import platform.UIKit.UIStatusBarStyle
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The iOS [SystemBarsController].
 *
 * iOS does not let anything set the status bar directly. A view controller *declares* what it
 * wants through `preferredStatusBarStyle` and `prefersStatusBarHidden`, and UIKit asks for those
 * values again when told the answer has changed. This controller is therefore the source of those
 * two answers, and the host view controller is the one that must expose them.
 *
 * Wiring it up is two steps, both on your side — the library does not reach into your view
 * hierarchy:
 *
 * 1. Point [hostViewController] at the controller hosting Compose, so that a configuration change
 *    can invalidate its status-bar appearance.
 * 2. Return [preferredStatusBarStyle] and [prefersStatusBarHidden] from that host's overrides.
 *
 * See `docs/kmptoolkit-systembars/05-platform-notes.md` for the exact code, and for why
 * `UIViewControllerBasedStatusBarAppearance` must stay at its default.
 */
public interface IosSystemBarsController : SystemBarsController {

    /**
     * The view controller whose status-bar appearance is invalidated when the configuration
     * changes. Held strongly; clear it (or [SystemBarsController.release] the controller) when that
     * host goes away.
     *
     * While it is `null` the configuration is still tracked — it simply has nowhere to be
     * delivered, and the next host to be assigned picks it up on its first appearance query.
     */
    public var hostViewController: UIViewController?

    /** Return this from your host's `preferredStatusBarStyle`. */
    public val preferredStatusBarStyle: UIStatusBarStyle

    /** Return this from your host's `prefersStatusBarHidden`. */
    public val prefersStatusBarHidden: Boolean
}

/**
 * Creates the iOS [SystemBarsController].
 *
 * @param initialConfig the base configuration to start from, before your theme sets one.
 */
public fun createSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
): IosSystemBarsController = IosSystemBarsControllerImpl(initialConfig)

private class IosSystemBarsControllerImpl(
    initialConfig: SystemBarsConfig,
) : LayeredSystemBarsController(initialConfig), IosSystemBarsController {

    override var hostViewController: UIViewController? = null

    override val preferredStatusBarStyle: UIStatusBarStyle
        get() = when (currentConfig.statusBarIcons) {
            SystemBarIconStyle.DarkIcons -> UIStatusBarStyleDarkContent
            SystemBarIconStyle.LightIcons -> UIStatusBarStyleLightContent
        }

    override val prefersStatusBarHidden: Boolean
        get() = !currentConfig.visibility.isStatusBarVisible

    override fun applyToPlatform(config: SystemBarsConfig) {
        // The values above are pulled by UIKit, not pushed, so all there is to do is tell it to
        // ask again — on the main thread, which is the only place UIKit may be touched.
        onMainThread { hostViewController?.setNeedsStatusBarAppearanceUpdate() }
    }

    override fun release() {
        hostViewController = null
        super.release()
    }

    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (NSThread.isMainThread()) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

@Composable
internal actual fun applyDialogWindowSystemBars(config: SystemBarsConfig) {
    // Nothing to do. A Compose dialog or sheet on iOS is drawn inside the same UIWindow as the rest
    // of the app and shares its one status bar, which IosSystemBarsController already drives.
}
