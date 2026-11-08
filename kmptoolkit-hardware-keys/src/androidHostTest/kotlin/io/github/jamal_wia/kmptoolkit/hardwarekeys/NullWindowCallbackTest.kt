package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Dialog
import android.view.Window
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** A window whose callback somebody cleared (`Window.setCallback(null)`) must not crash the effect entering it. */
@RunWith(AndroidJUnit4::class)
class NullWindowCallbackTest {

    @Test
    fun `a window whose callback was cleared by someone else can still be acquired and released`() {
        val window = Dialog(ApplicationProvider.getApplicationContext()).window!!
        window.callback = null
        val failure: Throwable? = runCatching { acquireInstallation(window) { false } }.exceptionOrNull()
        assertEquals(null, failure, "acquire on a window with no callback must not crash composition: $failure")
    }

    @Test
    fun `the window is left alone, release does not throw and a restored callback is wrapped on the next entry`() {
        val window = Dialog(ApplicationProvider.getApplicationContext()).window!!
        val original: Window.Callback = window.callback!!
        window.callback = null

        val hold: KeyPolicyRecord.Hold = acquireInstallation(window) { false }
        assertNull(window.callback, "a window with no callback is left alone")
        releaseInstallation(window, hold)
        assertNull(window.callback, "release leaves it alone too")

        window.callback = original
        acquireInstallation(window) { false }
        val top: Window.Callback? = window.callback
        assertTrue(top is KeyInterceptingWindowCallback, "entering again with a callback in place wraps it")
        assertSame(original, (top as KeyInterceptingWindowCallback).delegate)
    }
}
