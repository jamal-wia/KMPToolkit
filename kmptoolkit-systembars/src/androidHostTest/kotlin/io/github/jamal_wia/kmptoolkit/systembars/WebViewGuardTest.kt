package io.github.jamal_wia.kmptoolkit.systembars

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The API 24-25 `WebView` guard (plan issue-13 sections 2.2 and 6.6): below API 26 the layer reader
 * replays the content offscreen in a second hardware renderer, which blanks a `WebView`, so
 * [WebViewGuardedBarStripReader] skips a sample while one is attached in the composition's view
 * tree and the loop counts that as a failed attempt.
 *
 * Pinned at sdk 24, where the guard is the reader. The guarded reader is built here around a
 * counting inner reader, so the test proves the inner reader is never asked, not merely that
 * something was skipped. It proves nothing about a real `WebView` staying intact: only a device does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class WebViewGuardTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private class Host : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TestProbe : StatusBarLuminanceProbe {
        private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val triggers: SharedFlow<Unit> = flow
        override fun triggerRecalculation() {
            flow.tryEmit(Unit)
        }
    }

    /** A `WebView` subclass, as an ad SDK or an app would declare one. */
    private class AppWebView(context: Context) : WebView(context)

    private val host = Host()
    private val controller = RecordingSystemBarsController()
    private val inner = ScriptedBarStripReader()
    private val samples: MutableList<Long> = CopyOnWriteArrayList()

    private fun setProbe(withWebView: Boolean) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                val view: View = LocalView.current
                val reader = remember(view) { WebViewGuardedBarStripReader(view, inner) }
                AutoSystemBarsIconStyle(
                    controller = controller,
                    probe = TestProbe(),
                    enabled = true,
                    intervalMs = 300L,
                    onSampled = { samples += it },
                    reader = reader,
                ) {
                    if (withWebView) {
                        AndroidView(factory = { context -> WebView(context) }, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 64, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.STARTED }
        composeTestRule.waitForIdle()
        repeat(3) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun `a WebView in the composition makes every sample a skip that never reaches the inner reader`() {
        setProbe(withWebView = true)
        repeat(5) {
            composeTestRule.mainClock.advanceTimeBy(300L)
            composeTestRule.waitForIdle()
        }

        assertTrue(samples.size >= 5, "control: the loop kept attempting, attempts = ${samples.size}")
        assertEquals(0, inner.reads.get(), "the guard never let a read through to the layer reader")
        assertEquals(1, controller.activeOverrideCount, "the claim is held, not released")
    }

    @Test
    fun `the same screen without a WebView reads through the inner reader`() {
        setProbe(withWebView = false)

        assertEquals(1, inner.reads.get(), "the first tick read")
        assertEquals(1, controller.activeOverrideCount, "the claim exists")
        assertEquals(1, samples.size)
    }

    @Test
    fun `a detached WebView is not hosted`() {
        assertFalse(WebView(composeTestRule.activity).hostsAttachedWebView())
    }

    @Test
    fun `an attached WebView is hosted by itself`() {
        val webView = WebView(composeTestRule.activity)
        composeTestRule.runOnUiThread {
            composeTestRule.activity.addContentView(webView, ViewGroup.LayoutParams(10, 10))
        }
        assertTrue(webView.isAttachedToWindow, "control: attached")
        assertTrue(webView.hostsAttachedWebView())
    }

    @Test
    fun `a WebView nested two groups deep is found`() {
        val activity: ComponentActivity = composeTestRule.activity
        val webView = WebView(activity)
        val inner = FrameLayout(activity).apply { addView(webView) }
        val outer = FrameLayout(activity).apply { addView(inner) }
        composeTestRule.runOnUiThread { activity.addContentView(outer, ViewGroup.LayoutParams(10, 10)) }

        assertTrue(webView.isAttachedToWindow, "control: attached")
        assertTrue(outer.hostsAttachedWebView())
    }

    @Test
    fun `a WebView subclass is found`() {
        val activity: ComponentActivity = composeTestRule.activity
        val group = FrameLayout(activity).apply { addView(AppWebView(activity)) }
        composeTestRule.runOnUiThread { activity.addContentView(group, ViewGroup.LayoutParams(10, 10)) }

        assertTrue(group.hostsAttachedWebView())
    }

    @Test
    fun `a detached WebView inside an attached group is not hosted`() {
        val activity: ComponentActivity = composeTestRule.activity
        val group = FrameLayout(activity)
        val webView = WebView(activity)
        group.addView(webView)
        assertFalse(webView.isAttachedToWindow, "control: the group is not attached yet")
        assertFalse(group.hostsAttachedWebView())
    }

    @Test
    fun `a tree with no WebView is not hosting one`() {
        val activity: ComponentActivity = composeTestRule.activity
        val group = FrameLayout(activity).apply { addView(FrameLayout(activity).apply { addView(View(activity)) }) }
        composeTestRule.runOnUiThread { activity.addContentView(group, ViewGroup.LayoutParams(10, 10)) }

        assertFalse(group.hostsAttachedWebView())
    }
}
