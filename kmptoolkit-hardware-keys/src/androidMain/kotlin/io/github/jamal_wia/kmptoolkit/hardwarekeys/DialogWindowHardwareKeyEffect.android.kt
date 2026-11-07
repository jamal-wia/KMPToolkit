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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.e
import java.lang.ref.WeakReference
import java.util.WeakHashMap

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
     *
     * The interceptor comes last so the call reads as `install { event -> ... }`.
     */
    public fun install(logger: Logger = NoopLogger, interceptor: (KeyEvent) -> Boolean) {
        if (installed != null) {
            logger.e {
                "A dialog-window key policy is already installed; replacing it. install() belongs in " +
                    "Application.onCreate — registering per Activity or screen leaks the policy for " +
                    "the process lifetime."
            }
        }
        installed = interceptor
    }

    /**
     * Removes the policy. Windows composed afterwards get stock key handling; a window already in
     * composition keeps the interceptor it read when it was composed until it leaves composition (see
     * [install]). To switch a policy off at runtime - logout, a feature turning off - keep it installed
     * and return `false` from the lambda instead. Main thread only.
     */
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
    // Read once per window: a policy installed or removed later does not reach a window already composed,
    // whether or not this scope happens to recompose.
    val interceptor: ((KeyEvent) -> Boolean)? = remember(view, window) { DialogWindowHardwareKeyPolicy.interceptor }

    DisposableEffect(view, window, interceptor) {
        if (interceptor == null) {
            return@DisposableEffect onDispose { }
        }
        if (window != null) {
            // A window with a `Window.Callback` must be intercepted there: it is the only point ahead
            // of the `PhoneWindow` fallback that shows the system volume panel. An
            // OnUnhandledKeyEventListener would run too late (DecorView lets PhoneWindow handle the
            // key and reports it handled), and an OnKeyListener fires only while its view has focus.
            val installation: KeyInterceptingWindowCallback = acquireInstallation(window, interceptor)
            return@DisposableEffect onDispose { releaseInstallation(window, installation) }
        }
        // A focusable `Popup` has no Window and so no Window.Callback, which makes an unhandled-key
        // listener the right hook: `ViewRootImpl` offers unhandled keys to it precisely for windows
        // without a callback, before the system falls back to its own volume handling. Harmless in the
        // activity window too — whatever the Activity's onKeyDown consumed never reaches it.
        //
        // Only from API 28, where the platform dispatches these listeners. Below it, androidx emulates them
        // only for a ComponentActivity or ComponentDialog, and then *ahead of* Activity.onKeyDown — so in a
        // Popup the listener would never run, and in the Activity window it would take keys the Activity
        // meant to handle.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return@DisposableEffect onDispose { }
        }
        // Registered through the platform directly (this branch only runs from API 28), not through
        // ViewCompat: its remove drops the platform listener but leaves the entry it keeps in the view's
        // tag map, so every enter/leave would pin one more listener on the view.
        UnhandledKeyListeners.acquire(view, interceptor)
        onDispose { UnhandledKeyListeners.release(view) }
    }
}

/**
 * Takes a share of [window]'s decoration, creating it for the first effect. A second effect in the
 * same window — an app-wide dialog wrapper plus a shared component that calls it too — shares it
 * rather than decorating again, which would run the policy twice per key; and because each one holds
 * a share, the decoration outlives whichever of them leaves first.
 *
 * The decoration is found first through the window's own callback (it is the outermost one), and,
 * when another decorator was layered on top of it, through [WindowDecorations], so a foreign decorator
 * between two live effects does not hide it and a second live layer is never stacked. That registry
 * holds both the window and the decoration weakly: a strong value would be pinned by its own key
 * (the decoration reaches the window's previous callback, which for a `Dialog` is the `Dialog` itself
 * and holds the window, so a window whose effect was never disposed could not be collected), whereas
 * the decoration is reachable only through the window's callback chain, and a cleared reference means
 * our layer has left that chain. An inert decoration (no shares) that is not outermost is never
 * revived, because nothing proves it is still in the chain.
 * Touched only by effect enter and dispose, which Compose runs on the main thread.
 */
internal fun acquireInstallation(
    window: Window,
    interceptor: (KeyEvent) -> Boolean,
): KeyInterceptingWindowCallback {
    val outermost: KeyInterceptingWindowCallback? = window.callback as? KeyInterceptingWindowCallback
    if (outermost != null) {
        outermost.shares++
        return outermost
    }
    val registered: KeyInterceptingWindowCallback? = WindowDecorations.live(window)
    if (registered != null) {
        registered.shares++
        return registered
    }
    val decorated = KeyInterceptingWindowCallback(window.callback, interceptor)
    window.callback = decorated
    WindowDecorations.register(window, decorated)
    return decorated
}

/**
 * Which decoration of ours, if any, is in a window's callback chain, for [acquireInstallation] to find
 * when it is not the outermost. Weak key and weak value (see there); confined to the main thread by
 * Compose's effect scheduling, so it needs no lock and no atomic (D-21: thread confinement).
 */
private object WindowDecorations {
    private val byWindow: WeakHashMap<Window, WeakReference<KeyInterceptingWindowCallback>> = WeakHashMap()

    /** The decoration still in use (shares left) for [window], or `null`. */
    fun live(window: Window): KeyInterceptingWindowCallback? =
        byWindow[window]?.get()?.takeIf { decoration -> decoration.shares > 0 }

    fun register(window: Window, decoration: KeyInterceptingWindowCallback) {
        byWindow[window] = WeakReference(decoration)
    }
}

/**
 * One platform unhandled-key listener per view, shared by every effect composed on it (two effects in
 * the same activity content consult the policy once per key). The listener captures only the
 * interceptor, never the view, so the strong value does not pin its weak key. Main thread only, like
 * the effect (D-21: thread confinement, no lock).
 */
@RequiresApi(Build.VERSION_CODES.P)
private object UnhandledKeyListeners {
    private class Share(val listener: View.OnUnhandledKeyEventListener, var shares: Int)

    private val byView: WeakHashMap<View, Share> = WeakHashMap()

    fun acquire(view: View, interceptor: (KeyEvent) -> Boolean) {
        val existing: Share? = byView[view]
        if (existing != null) {
            existing.shares++
            return
        }
        val listener = View.OnUnhandledKeyEventListener { _, event -> interceptor(event) }
        view.addOnUnhandledKeyEventListener(listener)
        byView[view] = Share(listener, 1)
    }

    fun release(view: View) {
        val share: Share = byView[view] ?: return
        if (--share.shares > 0) return
        view.removeOnUnhandledKeyEventListener(share.listener)
        byView.remove(view)
    }
}

internal fun releaseInstallation(window: Window, installation: KeyInterceptingWindowCallback) {
    if (--installation.shares > 0) return
    // Restore only if nothing re-decorated the callback after us; clobbering another decorator would
    // silently drop its behaviour. When something did, the decoration stays in the chain with no share
    // left, and is inert: it forwards every key to its delegate without consulting the policy, so the
    // policy stops acting with the effect that installed it. An effect entering again under that
    // foreign decorator while another effect still holds the decoration shares it (WindowDecorations);
    // once none does, it stacks a new decoration on top, and the inert one below costs one forwarded call
    // per key, never a second run of the policy.
    if (window.callback === installation) window.callback = installation.delegate
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
    val delegate: Window.Callback,
    private val interceptor: (KeyEvent) -> Boolean,
) : Window.Callback by delegate {

    /**
     * How many effects hold this decoration; the last one to leave restores [delegate], or, when
     * another decorator sits on top and the callback cannot be restored, leaves this decoration inert
     * (zero shares). Main thread only.
     */
    var shares: Int = 1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (shares > 0 && interceptor(event)) || delegate.dispatchKeyEvent(event)

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
