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
import java.util.WeakHashMap

/**
 * Registration point for the app's policy on hardware key events inside Compose dialog-class
 * windows — see [DialogWindowHardwareKeyEffect] for why per-window installation is required.
 *
 * The app registers a single interceptor once, early — before any dialog window can open, which in
 * practice means `Application.onCreate` — and clears it when the policy no longer applies. Nothing
 * is registered by default, so an app that never calls [install] is completely unaffected.
 *
 * The interceptor is invoked on the **main thread**, once per key event. In a dialog window (a
 * `Dialog`, `ModalBottomSheet`, `DatePickerDialog`, anything with a `DialogWindowProvider`) it runs
 * before the dialog's own dispatch, and returning `true` consumes the event: the dialog's content, its
 * `Window.Callback`, and the `PhoneWindow` fallback (the one that would otherwise show the system volume
 * panel) never see it. In a focusable `Popup`, and in the Activity's own content on API 28 and higher,
 * the interceptor runs as the platform's unhandled-key listener instead, after the view tree has left
 * the key unhandled: a Compose key handler (`Modifier.onKeyEvent`) has already seen it, and `true` stops
 * only what the platform would do next. Return `false` for every event the policy does not care about —
 * the window then behaves exactly as if no interceptor existed.
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
            val hold: KeyPolicyRecord.Hold = acquireInstallation(window, interceptor)
            return@DisposableEffect onDispose { releaseInstallation(window, hold) }
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
        val hold: KeyPolicyRecord.Hold = UnhandledKeyListeners.acquire(view, interceptor)
        onDispose { UnhandledKeyListeners.release(view, hold) }
    }
}

/**
 * The policy in force for one window or view, and nothing else: who currently wants the policy and
 * which interceptor they read. It never refers to a layer, window, view or listener, only to
 * interceptors (application-scoped by the contract of [DialogWindowHardwareKeyPolicy.install]), so a
 * record held strongly as a `WeakHashMap` value cannot pin its key.
 *
 * Every effect that enters a window or view [take]s a [Hold] carrying the interceptor it read, and
 * [drop]s exactly that hold when it leaves. The policy in force is the interceptor read by the most
 * recently composed effect still holding: with one installed policy (every correct app) all holds carry
 * the same lambda; with a replaced one (reported at error level by [DialogWindowHardwareKeyPolicy.install])
 * the newest read wins, and an effect that leaves takes its own read with it.
 *
 * Main thread only: effects enter and leave there, and so does key dispatch (D-21: thread confinement,
 * no lock).
 */
internal open class KeyPolicyRecord {

    /** One effect's claim on the record. Identity equality: two effects never share a hold. */
    class Hold(val interceptor: (KeyEvent) -> Boolean)

    private val holds: ArrayList<Hold> = ArrayList()

    /** The interceptor of the most recently taken hold still held, or `null` when nobody holds the record. */
    val policy: ((KeyEvent) -> Boolean)?
        get() = holds.lastOrNull()?.interceptor

    fun take(interceptor: (KeyEvent) -> Boolean): Hold {
        val hold = Hold(interceptor)
        holds.add(hold)
        return hold
    }

    /**
     * Removes [hold] and returns whether the record is now empty. Idempotent: dropping a hold that is
     * not held (a second time) changes nothing and returns the current emptiness.
     */
    fun drop(hold: Hold): Boolean {
        val index: Int = holds.indexOfFirst { candidate -> candidate === hold }
        if (index >= 0) holds.removeAt(index)
        return holds.isEmpty()
    }
}

/**
 * The record of one dialog window plus the dispatch guard that makes the policy run once per key
 * however many of our [KeyInterceptingWindowCallback] layers the window's callback chain holds.
 */
internal class WindowKeyHook : KeyPolicyRecord() {

    // True while one of our layers is consulting the policy and forwarding the key. A plain field:
    // key dispatch and effects both run on the main thread (D-21: thread confinement).
    private var consulting: Boolean = false

    /**
     * Offers [event] to the policy once, then to [next]. A layer reached while an outer layer of the
     * same hook is already inside this call only forwards: the guard spans the forwarded call, so a
     * second layer (above or below a foreign decorator, revived or not) never asks again. The same
     * guard stops a policy that dispatches a key into this window from recursing into itself; the
     * accepted consequence is that a key dispatched to the same window from inside the handling of
     * another key is not offered to the policy again. A flag rather than event identity, because a
     * foreign decorator may copy the event between our layers. A layer with no holder (every effect
     * has left, the layer survives under a foreign decorator) is inert and only forwards.
     */
    fun dispatch(event: KeyEvent, next: Window.Callback): Boolean {
        if (consulting) return next.dispatchKeyEvent(event)
        val current: ((KeyEvent) -> Boolean) = policy ?: return next.dispatchKeyEvent(event)
        consulting = true
        try {
            return current(event) || next.dispatchKeyEvent(event)
        } finally {
            consulting = false
        }
    }
}

/**
 * One [WindowKeyHook] per window, kept for the window's lifetime (weak key; the entry is not removed
 * when the record empties) so every layer of ours ever put on that window shares one record and one
 * guard, including inert ones a foreign decorator buried. The value reaches only interceptors, never
 * the window, so it does not pin its key. Main thread only (D-21: thread confinement).
 */
private object WindowHooks {
    private val byWindow: WeakHashMap<Window, WindowKeyHook> = WeakHashMap()

    fun hookFor(window: Window): WindowKeyHook = byWindow.getOrPut(window) { WindowKeyHook() }
}

/**
 * Registers an effect's claim on [window]'s key policy and makes sure the window's callback chain
 * carries one of our layers on top.
 *
 * The rule is the whole algorithm: take a hold on the window's record, then, unless the outermost
 * callback is already a layer of this window's hook, put a new [KeyInterceptingWindowCallback] on top.
 * The chain below the top is never inspected, because a foreign decorator is opaque. A foreign
 * decorator on top therefore gets a layer of ours over it, so a layer a foreign "restore the saved
 * callback" dropped is replaced at the next entry, and a layer that is still in the chain below
 * shares the record and the guard, so the policy runs once per key however many layers there are.
 *
 * Layers accumulate only when a foreign decorator wraps *during every* effect life (our top layers are
 * peeled when the last hold leaves), which is the documented case of one inert wrapper per such cycle.
 * Touched only by effect enter and dispose, which Compose runs on the main thread.
 */
internal fun acquireInstallation(
    window: Window,
    interceptor: (KeyEvent) -> Boolean,
): KeyPolicyRecord.Hold {
    val hook: WindowKeyHook = WindowHooks.hookFor(window)
    val hold: KeyPolicyRecord.Hold = hook.take(interceptor)
    // A window whose callback someone cleared dispatches keys straight to its views; there is nothing to
    // wrap, so the policy does not act in it.
    val current: Window.Callback = window.callback ?: return hold
    if ((current as? KeyInterceptingWindowCallback)?.hook !== hook) {
        window.callback = KeyInterceptingWindowCallback(current, hook)
    }
    return hold
}

/**
 * Gives [hold] back. While another effect still holds the window nothing else happens. When the record
 * becomes empty, the layers of ours on top of the chain are peeled off, one by one, and the first
 * callback that is not ours stops it: a foreign decorator is never touched (clobbering it would
 * silently drop its behaviour), and our layers below it stay, inert (an empty record has no policy,
 * so they only forward). A second release of the same hold is a no-op.
 */
internal fun releaseInstallation(window: Window, hold: KeyPolicyRecord.Hold) {
    val hook: WindowKeyHook = WindowHooks.hookFor(window)
    if (!hook.drop(hold)) return
    while (true) {
        val top: KeyInterceptingWindowCallback = window.callback as? KeyInterceptingWindowCallback ?: return
        if (top.hook !== hook) return
        window.callback = top.delegate
    }
}

/**
 * One platform unhandled-key listener per view, shared by every effect composed on it, backed by a
 * [KeyPolicyRecord]: the listener asks the record, so the policy in force follows the effects composed
 * on that view (the most recently composed one still there decides). The listener captures the record,
 * never the view, so the strong entry does not pin its weak key. Main thread only, like the effect
 * (D-21: thread confinement, no lock).
 */
@RequiresApi(Build.VERSION_CODES.P)
private object UnhandledKeyListeners {
    private class Entry(val record: KeyPolicyRecord, val listener: View.OnUnhandledKeyEventListener)

    private val byView: WeakHashMap<View, Entry> = WeakHashMap()

    fun acquire(view: View, interceptor: (KeyEvent) -> Boolean): KeyPolicyRecord.Hold {
        val existing: Entry? = byView[view]
        if (existing != null) return existing.record.take(interceptor)
        val record = KeyPolicyRecord()
        val hold: KeyPolicyRecord.Hold = record.take(interceptor)
        val listener = View.OnUnhandledKeyEventListener { _, event -> record.policy?.invoke(event) ?: false }
        view.addOnUnhandledKeyEventListener(listener)
        byView[view] = Entry(record, listener)
        return hold
    }

    fun release(view: View, hold: KeyPolicyRecord.Hold) {
        val entry: Entry = byView[view] ?: return
        if (!entry.record.drop(hold)) return
        view.removeOnUnhandledKeyEventListener(entry.listener)
        byView.remove(view)
    }
}

/**
 * Forwards every [Window.Callback] member to [delegate] and overrides only key dispatch, so the
 * dialog keeps behaving normally for everything the policy declines. The policy is not held here but
 * in [hook], which every layer of one window shares and which guards the dispatch (see
 * [WindowKeyHook.dispatch]).
 *
 * The abstract members come from Kotlin interface delegation, but the two **Java default methods**
 * are forwarded by hand: `by` delegation generates no forwarders for them, so the wrapper would
 * silently answer with the interface's empty default instead of the dialog's own implementation.
 * `Dialog`'s implementations happen to be empty today, which is exactly why the omission would go
 * unnoticed until some platform version gives them behaviour.
 */
internal class KeyInterceptingWindowCallback(
    val delegate: Window.Callback,
    val hook: WindowKeyHook,
) : Window.Callback by delegate {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = hook.dispatch(event, delegate)

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
