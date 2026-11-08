package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Dialog
import android.view.KeyEvent
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The policy runs inside key dispatch and is allowed to do anything an app's key handler does: dismiss
 * the dialog (its effect leaves, peeling our layer from the window under the running dispatch), throw,
 * or forward the key to another dialog. None of it may leave the window's chain or the dispatch guard in
 * a state that costs the next key its policy, or runs the policy twice for one key.
 */
@RunWith(AndroidJUnit4::class)
class PolicyReentryAndFailureTest {

    // A dialog that was never shown has no decor view, so its own callback cannot dispatch a key; the
    // original callback is a stub that declines every key, which is what a dialog does for a key it ignores.
    private fun newWindow(): Window {
        val window: Window = Dialog(ApplicationProvider.getApplicationContext()).window!!
        val dialogCallback: Window.Callback = window.callback
        window.callback = object : Window.Callback by dialogCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = false
        }
        return window
    }

    private fun key(): KeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)

    @Test
    fun `a policy that releases its own installation mid dispatch leaves a clean window`() {
        val window: Window = newWindow()
        val original: Window.Callback = window.callback
        var calls = 0
        lateinit var hold: KeyPolicyRecord.Hold
        hold = acquireInstallation(window) { calls++; releaseInstallation(window, hold); false }

        assertFalse(window.callback.dispatchKeyEvent(key()))
        assertEquals(1, calls)
        assertSame(original, window.callback, "the layer was peeled while it was dispatching")

        // A later effect on the same window (the dialog is shown again) starts fresh: one layer, once per key.
        calls = 0
        val again: KeyPolicyRecord.Hold = acquireInstallation(window) { calls++; false }
        window.callback.dispatchKeyEvent(key())
        assertEquals(1, calls, "the policy must run exactly once for the next key")
        releaseInstallation(window, again)
        assertSame(original, window.callback)
    }

    @Test
    fun `a policy that throws does not stick the dispatch guard`() {
        val window: Window = newWindow()
        var calls = 0
        acquireInstallation(window) { calls++; if (calls == 1) error("policy failure"); false }

        runCatching { window.callback.dispatchKeyEvent(key()) }
        window.callback.dispatchKeyEvent(key())

        assertEquals(2, calls, "after a failing key the policy must still be asked for the next one")
    }

    @Test
    fun `a policy that forwards the key to another dialog consults that dialog's policy once and its own once`() {
        val a: Window = newWindow()
        val b: Window = newWindow()
        var callsA = 0
        var callsB = 0
        acquireInstallation(b) { callsB++; false }
        acquireInstallation(a) { callsA++; b.callback.dispatchKeyEvent(it); false }

        a.callback.dispatchKeyEvent(key())

        assertEquals(1, callsA)
        assertEquals(1, callsB, "the second dialog's own policy is a different record and must be asked")
        callsA = 0; callsB = 0
        b.callback.dispatchKeyEvent(key())
        assertEquals(0, callsA)
        assertEquals(1, callsB)
    }

    @Test
    fun `an effect entering the window from inside the policy does not double the policy for later keys`() {
        val window: Window = newWindow()
        var calls = 0
        var entered: KeyPolicyRecord.Hold? = null
        val policy: (KeyEvent) -> Boolean = {
            calls++
            if (entered == null) entered = acquireInstallation(window) { calls++; false }
            false
        }
        acquireInstallation(window, policy)

        window.callback.dispatchKeyEvent(key())
        calls = 0
        window.callback.dispatchKeyEvent(key())

        assertEquals(1, calls, "two holds on one window: the newest decides and it runs once")
        assertTrue(entered != null)
    }
}
