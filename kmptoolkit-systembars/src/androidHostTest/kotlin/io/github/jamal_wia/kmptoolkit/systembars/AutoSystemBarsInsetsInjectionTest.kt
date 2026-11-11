package io.github.jamal_wia.kmptoolkit.systembars

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.assertEquals
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The probe's gate on API 24 and 28 (the levels [AutoSystemBarsIconStyleModelFuzzTest] is pinned
 * above): the status-bar inset the tests dispatch to the host view reaches `WindowInsets.statusBars`,
 * and a probe that sees it claims the status-bar icons exactly once, also after its composition
 * left and returned. The sampler is gated on that inset, so a failure here would be the product's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 35])
class AutoSystemBarsInsetsInjectionTest {

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

    private fun inject(top: Int) {
        composeTestRule.runOnUiThread {
            val value: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), value)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a status bar inset dispatched to the host view reaches WindowInsets statusBars`() {
        var seenTop: Int = -1
        composeTestRule.setContent {
            val density = LocalDensity.current
            seenTop = WindowInsets.statusBars.getTop(density)
            Box(Modifier.fillMaxSize())
        }
        inject(64)
        assertEquals(64, seenTop, "WindowInsets.statusBars after the injection")
        inject(0)
        assertEquals(0, seenTop, "WindowInsets.statusBars after the inset is removed")
    }

    @Test
    fun `a probe that sees a status bar inset claims the status bar icons`() {
        val controller = RecordingSystemBarsController()
        val host = Host()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(controller = controller, probe = TestProbe()) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        inject(64)
        repeat(6) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
        assertEquals(1, controller.activeOverrideCount, "claims with a 64 px status bar inset in place")
    }

    @Test
    fun `after the composition leaves and returns the injected inset is read back and claimed`() {
        val controller = RecordingSystemBarsController()
        val host = Host()
        var shown: Boolean by mutableStateOf(true)
        var seenTop: Int = -1
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            if (shown) {
                CompositionLocalProvider(LocalLifecycleOwner provides host) {
                    AutoSystemBarsIconStyle(controller = controller, probe = TestProbe()) {
                        val density = LocalDensity.current
                        seenTop = WindowInsets.statusBars.getTop(density)
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        inject(0)
        composeTestRule.runOnUiThread { shown = false }
        composeTestRule.waitForIdle()
        inject(64)
        composeTestRule.runOnUiThread { shown = true }
        composeTestRule.waitForIdle()
        repeat(2) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
        inject(64)
        repeat(4) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
        assertEquals(64, seenTop, "WindowInsets.statusBars after the composition returned and 64 was dispatched")
        assertEquals(1, controller.activeOverrideCount, "claims after the composition returned")
    }
}
