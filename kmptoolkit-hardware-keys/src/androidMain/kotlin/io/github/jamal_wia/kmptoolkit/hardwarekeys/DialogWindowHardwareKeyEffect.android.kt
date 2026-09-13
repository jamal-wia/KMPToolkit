package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.os.Build
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.e

/**
 * Registration point for the app's policy on hardware key events inside Compose dialog-class
 * windows — see [DialogWindowHardwareKeyEffect] for why per-window installation is required.
 *
 * The app registers a single interceptor once, early — before any dialog window can open, which in
 * practice means `Application.onCreate` — and clears it when the policy no longer applies. Nothing
 * is registered by default, so an app that never calls [install] is completely unaffected.
 *
 * The interceptor is invoked on the **main thread**, once per key event, before the dialog's own
 * dispatch. Returning `true` consumes the event: the dialog's content, its `Window.Callback`, and the
 * `PhoneWindow` fallback (the one that would otherwise show the system volume panel) never see it.
 * Return `false` for every event the policy does not care about — the dialog then behaves exactly as
 * if no interceptor existed.
 *
 * This is a process-wide registration rather than a factory-made instance on purpose: a dialog
 * window can be composed anywhere in the tree, including from code that has no route to an app-level
 * object, and the policy it must honour is by nature a property of the process, not of a screen.
 */
public object DialogWindowHardwareKeyPolicy {

    private var installed: ((KeyEvent) -> Boolean)? = null

    /** Read by [DialogWindowHardwareKeyEffect] when a window enters composition. Main thread only. */
    internal val interceptor: ((KeyEvent) -> Boolean)? get() = installed

    /**
     * Installs the process-wide policy. Call **once, from `Application.onCreate`** on the main thread,
     * before any window can open: a window already in composition when this changes keeps whatever
     * was in force when it was composed. A policy whose applicability varies at runtime should be
     * installed unconditionally and decide inside the lambda.
     *
     * **The lambda is retained for the whole process lifetime.** It must therefore capture only
     * application-scoped objects — never an `Activity`, `View`, `Window`, composition, or anything
     * holding one; capturing UI state here would survive every Activity recreation and leak it. For
     * the same reason installing a second policy while one is in force is reported to [logger] at
     * error level — it is a strong hint that a screen, not the application, is registering it — and
     * the new one replaces the old.
     */
    public fun install(interceptor: (KeyEvent) -> Boolean, logger: Logger = NoopLogger) {
        if (installed != null) {
            logger.e {
                "A dialog-window key policy is already installed; replacing it. install() belongs in " +
                    "Application.onCreate — registering per Activity or screen leaks the policy for " +
                    "the process lifetime."
            }
        }
        installed = interceptor
    }

    /** Removes the policy, restoring completely stock dialog-window key handling. Main thread only. */
    public fun uninstall() {
        installed = null
    }
}

@Composable
public actual fun DialogWindowHardwareKeyEffect() {
    val view: View = LocalView.current
    // Non-null in a Dialog / ModalBottomSheet / DatePickerDialog, which are backed by an Android
    // `Dialog` and so own a `Window`; null in a `Popup` — a bare view added straight to the
    // WindowManager — and in regular activity content.
    val window: Window? = (view.parent as? DialogWindowProvider)?.window
    val interceptor: ((KeyEvent) -> Boolean)? = DialogWindowHardwareKeyPolicy.interceptor

    DisposableEffect(view, window, interceptor) {
        if (interceptor == null) {
            return@DisposableEffect onDispose { }
        }
        if (window != null) {
            // A window with a `Window.Callback` must be intercepted there: it is the only point ahead
            // of the `PhoneWindow` fallback that shows the system volume panel. An
            // OnUnhandledKeyEventListener would run too late (DecorView lets PhoneWindow handle the
            // key and reports it handled), and an OnKeyListener fires only while its view has focus.
            val original: Window.Callback = window.callback
            val decorated = KeyInterceptingWindowCallback(original, interceptor)
            window.callback = decorated
            return@DisposableEffect onDispose {
                // Restore only if nothing re-decorated the callback after us; clobbering another
                // decorator would silently drop its behaviour.
                if (window.callback === decorated) window.callback = original
            }
        }
        // A focusable `Popup` has no Window and so no Window.Callback, which makes an unhandled-key
        // listener the right hook: `ViewRootImpl` offers unhandled keys to it precisely for windows
        // without a callback, before the system falls back to its own volume handling. Harmless in the
        // activity window too — whatever the Activity's onKeyDown consumed never reaches it.
        val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event -> interceptor(event) }
        ViewCompat.addOnUnhandledKeyEventListener(view, listener)
        onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
    }
}

/**
 * Forwards every [Window.Callback] member to [delegate] and overrides only key dispatch, so the
 * dialog keeps behaving normally for everything the [interceptor] declines.
 *
 * The abstract members come from Kotlin interface delegation, but the two **Java default methods**
 * are forwarded by hand: `by` delegation generates no forwarders for them, so the wrapper would
 * silently answer with the interface's empty default instead of the dialog's own implementation.
 * `Dialog`'s implementations happen to be empty today, which is exactly why the omission would go
 * unnoticed until some platform version gives them behaviour.
 */
internal class KeyInterceptingWindowCallback(
    private val delegate: Window.Callback,
    private val interceptor: (KeyEvent) -> Boolean,
) : Window.Callback by delegate {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        interceptor(event) || delegate.dispatchKeyEvent(event)

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        delegate.onPointerCaptureChanged(hasCapture)
    }
}
