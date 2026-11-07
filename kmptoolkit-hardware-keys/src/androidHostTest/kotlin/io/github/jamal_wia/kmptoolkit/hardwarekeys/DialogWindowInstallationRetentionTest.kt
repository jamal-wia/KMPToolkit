package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.app.Dialog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the per-window decoration pins. A dialog whose effect was never disposed — an abandoned
 * composition — must be collectable, and sharing must keep working without a global registry.
 */
@RunWith(AndroidJUnit4::class)
class DialogWindowInstallationRetentionTest {

    private fun newDialog(): Dialog = Dialog(ApplicationProvider.getApplicationContext())

    @Test
    fun `a window whose installation was never released is collectable`() {
        var dialog: Dialog? = newDialog()
        acquireInstallation(dialog!!.window!!) { false }
        val reference = WeakReference(dialog)
        dialog = null

        repeat(20) {
            if (reference.get() == null) return@repeat
            System.gc()
            Thread.sleep(25)
        }

        assertNull(reference.get(), "the dialog is still reachable: something global pins its window")
    }

    @Test
    fun `a second acquire shares the decoration and the last release restores the original callback`() {
        val window = newDialog().window!!
        val original = window.callback

        val first = acquireInstallation(window) { false }
        val second = acquireInstallation(window) { true }
        assertSame(first, second)
        releaseInstallation(window, first)
        assertSame(first, window.callback)
        releaseInstallation(window, second)

        assertSame(original, window.callback)
    }

    @Test
    fun `a release does not clobber a decorator installed after ours`() {
        val window = newDialog().window!!
        val ours = acquireInstallation(window) { false }
        val later = KeyInterceptingWindowCallback(window.callback) { false }
        window.callback = later

        releaseInstallation(window, ours)

        assertSame(later, window.callback)
    }

    @Test
    fun `acquiring again after a full release decorates afresh`() {
        val window = newDialog().window!!
        val first = acquireInstallation(window) { false }
        releaseInstallation(window, first)

        val second = acquireInstallation(window) { false }

        assertFalse(first === second)
        assertNotNull(window.callback)
        assertTrue(window.callback === second)
    }
}
