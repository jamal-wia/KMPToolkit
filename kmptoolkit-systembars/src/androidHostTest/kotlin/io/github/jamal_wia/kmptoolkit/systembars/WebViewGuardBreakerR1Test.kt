package io.github.jamal_wia.kmptoolkit.systembars

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jamal_wia.kmptoolkit.systembars.ScriptedBarStripReader.Step
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sb-core (ticket release-6-0-0, items SB-1/SB-2): the API 24-25
 * [WebViewGuardedBarStripReader] inside the real sampling loop, attacked where the executor's tests
 * stop (plan issue-13 sections 2.2, 2.5, 10 item 4; decision D-45).
 *
 * - The guard's draw hook must hand the frame to the inner (layer) reader exactly once: a guard that
 *   draws the content itself never records the layer, so every API 24-25 read would be an empty-layer
 *   skip and the probe would silently die after three of them.
 * - A guard skip is a failed attempt: a decided screen that gains a `WebView` withdraws the claim after
 *   three skips (the plan's "dark screen, then a white WebView page" case), and removing the `WebView`
 *   reads and republishes again ("a WebView attached later is caught by the next walk", and released).
 * - Visibility is deliberately not tested: an INVISIBLE but attached `WebView` still skips.
 * - D-45: a `WebView` anywhere in the same Compose view, also outside the wrapped content, skips.
 *
 * Mutations that turn this red (by reading): the guard's `drawContentAndCapture` calling
 * `drawContent()` instead of the inner hook (or both); an `isShown`/visibility test in
 * `hostsAttachedWebView`; a walk that starts at the probe's content instead of `LocalView`; a guard
 * skip that is not counted toward `FAILED_READS_BEFORE_RELEASE`; a guard that caches its first answer.
 *
 * Every attempt is a trigger (the periodic interval is far beyond the test), so each step is exactly
 * one attempt. Compose test clock, `autoAdvance = false`, no sleeps, no wall clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class WebViewGuardBreakerR1Test {

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

    private val host = Host()
    private val probe = TestProbe()
    private val controller = RecordingSystemBarsController(BASE)
    private val styled = BarStripStyles(SystemBarIconStyle.LightIcons, null)
    private val inner = ScriptedBarStripReader(fallback = Step.Styles(styled))
    private val samples: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList()
    private val contentDraws = AtomicInteger()

    private fun injectStatusBar() {
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 64, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    private fun start() {
        composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.STARTED }
        composeTestRule.waitForIdle()
        advanceFrames(FRAMES_PER_ATTEMPT)
    }

    /** One triggered attempt; asserts it happened. */
    private fun attempt() {
        val before: Int = samples.size
        probe.triggerRecalculation()
        advanceFrames(FRAMES_PER_ATTEMPT)
        assertEquals(before + 1, samples.size, "control: the trigger made exactly one attempt")
    }

    @androidx.compose.runtime.Composable
    private fun GuardedProbe(content: @androidx.compose.runtime.Composable () -> Unit) {
        val view: View = LocalView.current
        val reader = remember(view) { WebViewGuardedBarStripReader(view, inner) }
        AutoSystemBarsIconStyle(
            controller = controller,
            probe = probe,
            enabled = true,
            intervalMs = NO_PERIODIC_TICK_MS,
            onSampled = { samples += it },
            reader = reader,
            content = content,
        )
    }

    @Test
    fun `the guard hands every root draw to the inner reader exactly once and the content is drawn once`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            GuardedProbe { Box(Modifier.fillMaxSize().drawBehind { contentDraws.incrementAndGet() }) }
        }
        composeTestRule.waitForIdle()
        val innerBefore: Int = inner.draws.get()
        val contentBefore: Int = contentDraws.get()

        composeTestRule.runOnUiThread {
            val decor: View = composeTestRule.activity.window.decorView
            val bitmap: Bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
        }

        val hookCalls: Int = inner.draws.get() - innerBefore
        assertTrue(hookCalls > 0, "the inner reader's hook ran (the layer reader records here), calls = $hookCalls")
        assertEquals(hookCalls, contentDraws.get() - contentBefore, "the content is drawn only through the inner hook, once per call")
    }

    @Test
    fun `a decided screen that gains a WebView withdraws the claim after three skips and recovers once it is gone`() {
        var webView: Boolean by mutableStateOf(false)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                GuardedProbe {
                    if (webView) AndroidView(factory = { WebView(it) }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        injectStatusBar()
        start()
        assertEquals(1, inner.reads.get(), "control: the first tick read through")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons, "control: decided")

        composeTestRule.runOnUiThread { webView = true }
        composeTestRule.waitForIdle()
        attempt()
        attempt()
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons, "two skips hold the claim")
        attempt()

        assertEquals(1, inner.reads.get(), "no skip reached the layer reader")
        assertEquals(BASE, controller.currentConfig, "three guard skips withdrew the claim (a guard skip is a failed attempt)")

        composeTestRule.runOnUiThread { webView = false }
        composeTestRule.waitForIdle()
        attempt()

        assertEquals(2, inner.reads.get(), "with the WebView detached the next walk lets the read through")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons, "and it republished")
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `an invisible but attached WebView still skips the sample`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                GuardedProbe {
                    AndroidView(
                        factory = { WebView(it).apply { visibility = View.INVISIBLE } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        injectStatusBar()
        start()
        attempt()

        assertTrue(samples.size >= 2, "control: attempts were made")
        assertEquals(0, inner.reads.get(), "the replayed display list may still hold the functor: skip")
    }

    @Test
    fun `a WebView outside the wrapped content but in the same Compose view skips the sample`() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                Column(Modifier.fillMaxSize()) {
                    AndroidView(factory = { WebView(it) }, modifier = Modifier.size(10.dp))
                    GuardedProbe { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        injectStatusBar()
        start()
        attempt()

        assertTrue(samples.size >= 2, "control: attempts were made")
        assertEquals(0, inner.reads.get(), "D-45: anywhere in the same Compose view pauses sampling")
    }

    private companion object {
        val BASE: SystemBarsConfig = SystemBarsConfig.ForLightBackground
        const val FRAMES_PER_ATTEMPT = 3
        const val NO_PERIODIC_TICK_MS = 3_600_000L
    }
}
