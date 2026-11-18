package io.github.jamal_wia.kmptoolkit.systembars

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlin.test.assertEquals

/**
 * Breaker round 1 of group sb-core (ticket release-6-0-0, items SB-1/SB-2): the API 24-25 path end to
 * end through the PUBLIC composable, which picks its reader with `rememberBarStripReader()` (plan
 * issue-13 sections 2.2, 3.3, 4: "Android 24-25: `WebViewGuardedBarStripReader(LayerBarStripReader)`,
 * records `layer`, draws it"). The executor's end-to-end test runs at sdk 35 only and the selection
 * test checks the guard's class, not what it wraps, so nothing proved that API 24-25 still samples.
 *
 * Robolectric never runs the root draw pass on its own, so the test draws the decor once in software:
 * that is the frame the layer reader records. A white screen must then give dark status icons.
 *
 * Mutations that turn this red (by reading): the API 24-25 branch wrapping anything but a layer
 * reader that records in its draw hook (a reader that always answers `null`, a guard whose draw hook
 * skips the inner reader); the guard reporting a `WebView` where there is none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 25])
class LayerReaderEndToEndBreakerR1Test {

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

    @Test
    fun `a white screen on API 24-25 gives dark status icons through the selected reader`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val host = Host()
        val probe = TestProbe()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(controller = controller, probe = probe) {
                    Box(Modifier.fillMaxSize().background(Color.White))
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

        // The frame the layer reader records: one software draw of the whole decor.
        composeTestRule.runOnUiThread {
            val decor: View = composeTestRule.activity.window.decorView
            val bitmap: Bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
        }
        probe.triggerRecalculation()
        repeat(6) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "white under the status bar")
    }
}
