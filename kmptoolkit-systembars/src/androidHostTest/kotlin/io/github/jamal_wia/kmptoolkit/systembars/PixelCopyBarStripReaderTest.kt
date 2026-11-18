package io.github.jamal_wia.kmptoolkit.systembars

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The real Android window reader, end to end through Robolectric's `ShadowPixelCopy` (plan issue-13
 * section 6.7): white top half, black bottom half, a status inset and a navigation inset injected as
 * the other probe tests do, and `controller.config` must show dark icons over the white status strip
 * and light icons over the black navigation strip. Window lookup, rectangle, destination geometry and
 * luma plumbing all run for real.
 *
 * Under `@GraphicsMode(NATIVE)` with `robolectric.pixelCopyRenderMode=software` the shadow's screenshot
 * does paint Compose content, so the assertion is pixel-true: swapping the two halves turns the test
 * red (checked when the test was written). The test is not narrowed to plumbing. The plumbing cases
 * (rectangle, destination, codes, cancellation) are in `PixelCopyBarStripReaderPlumbingTest`.
 *
 * What this proves is the code path through the shadow, nothing about a real Chromium or a real
 * `PixelCopy`: the device row of RELEASING.md owns that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PixelCopyBarStripReaderTest {

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

    private var savedRenderMode: String? = null

    @Before
    fun selectSoftwareRendering() {
        savedRenderMode = System.getProperty(RENDER_MODE_PROPERTY)
        System.setProperty(RENDER_MODE_PROPERTY, "software")
    }

    @After
    fun restoreRenderMode() {
        val saved: String? = savedRenderMode
        if (saved == null) System.clearProperty(RENDER_MODE_PROPERTY) else System.setProperty(RENDER_MODE_PROPERTY, saved)
    }

    @Test
    fun `a white top half and a black bottom half give dark status icons and light navigation icons`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val host = Host()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                AutoSystemBarsIconStyle(controller = controller, probe = TestProbe()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color.White))
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black))
                    }
                }
            }
        }
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 64, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 64))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { host.registry.currentState = Lifecycle.State.STARTED }
        composeTestRule.waitForIdle()
        repeat(6) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "bright strip under the status bar")
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons, "dark strip under the navigation bar")
    }

    /**
     * The premise of the platform-IAE handling, pinned on the test double itself: when this changes
     * in a later Robolectric the reader's `IllegalArgumentException` branch needs a new reference.
     */
    @Test
    fun `the shadow rejects an empty source rectangle the way the platform does`() {
        val window: Window = composeTestRule.activity.window
        val destination: Bitmap = Bitmap.createBitmap(SAMPLE_COLS, 4, Bitmap.Config.ARGB_8888)

        assertFailsWith<IllegalArgumentException> {
            PixelCopy.request(window, Rect(), destination, { }, Handler(Looper.getMainLooper()))
        }
    }

    private companion object {
        const val RENDER_MODE_PROPERTY = "robolectric.pixelCopyRenderMode"
    }
}
