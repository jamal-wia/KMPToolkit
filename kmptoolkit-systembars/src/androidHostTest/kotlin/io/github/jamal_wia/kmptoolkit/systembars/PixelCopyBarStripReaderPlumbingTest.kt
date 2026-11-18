package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android window reader's plumbing (plan issue-13 sections 3.3 and 6.7), through a [WindowCopier]
 * test double that records every request and holds the platform listener: what is asked of the
 * window (source rectangle, `8 × R` destination, main-looper handler), how every answer maps to a
 * read, and what a listener that fires after its caller was cancelled does. It says nothing about
 * what a real `PixelCopy` returns; `PixelCopyBarStripReaderTest` runs the real call through
 * Robolectric's `ShadowPixelCopy`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PixelCopyBarStripReaderPlumbingTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** One recorded `copy` call. */
    private class Request(
        val window: Window,
        val source: Rect,
        val destination: Bitmap,
        val listener: PixelCopy.OnPixelCopyFinishedListener,
        val handler: Handler,
    )

    /** Records requests; [onRequest] decides what happens to each (answer now, throw, or hold). */
    private class RecordingCopier(val onRequest: (Request) -> Unit = {}) : WindowCopier {
        val requests: CopyOnWriteArrayList<Request> = CopyOnWriteArrayList()

        override fun copy(
            window: Window,
            source: Rect,
            destination: Bitmap,
            listener: PixelCopy.OnPixelCopyFinishedListener,
            handler: Handler,
        ) {
            val request = Request(window, source, destination, listener, handler)
            requests.add(request)
            onRequest(request)
        }
    }

    private fun composedView(): View {
        var view: View? = null
        composeTestRule.setContent { view = captureView() }
        composeTestRule.waitForIdle()
        return assertNotNull(view, "the composition's view")
    }

    @Composable
    private fun captureView(): View = LocalView.current

    private fun decorHeight(): Int = composeTestRule.activity.window.decorView.height

    @Test
    fun `the request asks for the whole decor, an 8 by R destination and the main looper`() = runTest {
        val view: View = composedView()
        val decor: View = composeTestRule.activity.window.decorView
        assertTrue(decor.width > 0 && decor.height > 0, "control: the host decor has a size, ${decor.width} x ${decor.height}")
        val copier = RecordingCopier { it.listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
        val reader = PixelCopyBarStripReader(view, copier)

        reader.read(statusPx = 64, navPx = 96)

        val request: Request = copier.requests.single()
        val rows: BarStripRows = assertNotNull(barStripRows(decorHeight(), 64, 96))
        assertSame(composeTestRule.activity.window, request.window)
        assertEquals(Rect(0, 0, decor.width, decor.height), request.source, "the whole decor, in window coordinates")
        assertEquals(SAMPLE_COLS, request.destination.width)
        assertEquals(rows.destRows, request.destination.height)
        assertEquals(Bitmap.Config.ARGB_8888, request.destination.config)
        assertSame(Looper.getMainLooper(), request.handler.looper, "the listener runs on the composition's thread")
    }

    @Test
    fun `every request gets its own destination and its own rectangle`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier { it.listener.onPixelCopyFinished(PixelCopy.SUCCESS) }
        val reader = PixelCopyBarStripReader(view, copier)

        reader.read(64, 64)
        reader.read(64, 64)

        val (first: Request, second: Request) = copier.requests.toList()
        assertTrue(first.destination !== second.destination, "a cancelled request may still be writing its bitmap")
        assertTrue(first.source !== second.source, "the framework offsets the rectangle in place")
    }

    @Test
    fun `a successful copy is read as styles per bar from the rows the geometry assigned`() = runTest {
        val view: View = composedView()
        val rows: BarStripRows = assertNotNull(barStripRows(decorHeight(), 64, 64))
        // White under the status bar, black under the navigation bar, mid grey elsewhere.
        val copier = RecordingCopier { request ->
            request.destination.eraseColor(Color.rgb(128, 128, 128))
            for (row in rows.status) for (x in 0 until SAMPLE_COLS) request.destination.setPixel(x, row, Color.WHITE)
            for (row in rows.nav) for (x in 0 until SAMPLE_COLS) request.destination.setPixel(x, row, Color.BLACK)
            request.listener.onPixelCopyFinished(PixelCopy.SUCCESS)
        }

        val styles: BarStripStyles? = PixelCopyBarStripReader(view, copier).read(64, 64)

        assertNotNull(styles)
        assertEquals(SystemBarIconStyle.DarkIcons, styles.status, "a bright strip needs dark icons")
        assertEquals(SystemBarIconStyle.LightIcons, styles.nav, "a dark strip needs light icons")
    }

    @Test
    fun `a bar without a strip has no style while the other is read`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier { request ->
            request.destination.eraseColor(Color.WHITE)
            request.listener.onPixelCopyFinished(PixelCopy.SUCCESS)
        }

        val styles: BarStripStyles? = PixelCopyBarStripReader(view, copier).read(statusPx = 64, navPx = 0)

        assertNotNull(styles)
        assertEquals(SystemBarIconStyle.DarkIcons, styles.status)
        assertNull(styles.nav, "no navigation strip, no decision")
    }

    @Test
    fun `every non success code is a skip`() = runTest {
        val view: View = composedView()
        val codes: List<Int> = listOf(
            PixelCopy.ERROR_UNKNOWN,
            PixelCopy.ERROR_TIMEOUT,
            PixelCopy.ERROR_SOURCE_NO_DATA,
            PixelCopy.ERROR_SOURCE_INVALID,
            PixelCopy.ERROR_DESTINATION_INVALID,
        )
        for (code in codes) {
            val copier = RecordingCopier { it.listener.onPixelCopyFinished(code) }
            assertNull(PixelCopyBarStripReader(view, copier).read(64, 64), "PixelCopy result $code must be a skipped read")
            assertEquals(1, copier.requests.size, "control: the request was made for $code")
        }
    }

    @Test
    fun `an IllegalArgumentException from the request is a skip`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier { throw IllegalArgumentException("sourceRect is empty") }

        assertNull(PixelCopyBarStripReader(view, copier).read(64, 64))
        assertEquals(1, copier.requests.size, "control: the request was attempted")
    }

    @Test
    fun `another exception from the request is left to the loop`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier { throw IllegalStateException("surface lost") }

        assertFailsWith<IllegalStateException> { PixelCopyBarStripReader(view, copier).read(64, 64) }
    }

    @Test
    fun `a listener that fires after the caller was cancelled is ignored`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier()
        val read = async(start = CoroutineStart.UNDISPATCHED) { PixelCopyBarStripReader(view, copier).read(64, 64) }
        val request: Request = assertNotNull(copier.requests.singleOrNull(), "control: the copy is in flight")

        read.cancel()
        request.listener.onPixelCopyFinished(PixelCopy.SUCCESS)

        assertTrue(read.isCancelled, "the read ended by cancellation")
        assertFailsWith<CancellationException> { read.await() }
        assertEquals(1, copier.requests.size, "a cancelled read does not ask again")
    }

    @Test
    fun `a view that is not the window's root is a skip and asks nothing`() = runTest {
        composedView()
        val bare = View(composeTestRule.activity)
        val copier = RecordingCopier()

        assertNull(PixelCopyBarStripReader(bare, copier).read(64, 64))
        assertEquals(0, copier.requests.size, "a detached view is not drawn into the window buffer")
    }

    @Test
    fun `a view with no window behind it is a skip and asks nothing`() = runTest {
        val detached = View(composeTestRule.activity.applicationContext)
        val copier = RecordingCopier()

        assertNull(PixelCopyBarStripReader(detached, copier).read(64, 64))
        assertEquals(0, copier.requests.size)
    }

    @Test
    fun `both strips zero asks nothing`() = runTest {
        val view: View = composedView()
        val copier = RecordingCopier()

        assertNull(PixelCopyBarStripReader(view, copier).read(0, 0))
        assertEquals(0, copier.requests.size)
    }

    @Test
    fun `a decor that was never laid out is a skip and asks nothing`() = runTest {
        val activity: Activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        val content = View(activity)
        activity.setContentView(content)
        val decor: View = activity.window.decorView
        assertEquals(0, decor.width, "control: no layout has run, width ${decor.width}")
        val copier = RecordingCopier()

        assertNull(PixelCopyBarStripReader(content, copier).read(64, 64))
        assertEquals(0, copier.requests.size)
    }

    @Test
    fun `hostWindow finds the activity through a chain of context wrappers`() {
        val activity: Activity = composeTestRule.activity
        val wrapped: Context = ContextWrapper(ContextWrapper(activity))

        assertSame(activity.window, View(wrapped).hostWindow())
    }

    @Test
    fun `hostWindow prefers the dialog window of a DialogWindowProvider parent`() {
        val activity: Activity = composeTestRule.activity
        val dialogWindow: Window = Robolectric.buildActivity(ComponentActivity::class.java).create().get().window
        val parent = object : FrameLayout(activity), DialogWindowProvider {
            override val window: Window = dialogWindow
        }
        val child = View(activity)
        parent.addView(child)

        assertSame(dialogWindow, child.hostWindow())
    }

    @Test
    fun `hostWindow is null without an activity or a dialog parent`() {
        assertNull(View(composeTestRule.activity.applicationContext).hostWindow())
    }
}
