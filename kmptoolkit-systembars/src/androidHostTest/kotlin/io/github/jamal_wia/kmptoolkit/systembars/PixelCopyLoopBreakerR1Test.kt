package io.github.jamal_wia.kmptoolkit.systembars

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Popup
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
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sb-core (ticket release-6-0-0, items SB-1/SB-2): the REAL Android window
 * reader ([PixelCopyBarStripReader]) driven by the REAL sampling loop, with a [WindowCopier] that holds
 * each platform listener so the test decides when (and whether) the copy answers.
 *
 * Contract attacked (plan issue-13 sections 2.5, 2.7, 3.3 step 6, 4): a copy that is still in flight
 * when the sampler is cancelled (host below STARTED, composable leaving) must publish nothing, book
 * nothing and call no `onSampled` when its listener fires late; a listener left over from an earlier
 * cycle must never answer the current cycle's copy (stale-callback fencing); the next cycle asks again
 * with a fresh destination; three failed copies withdraw the claim; the draw hook draws the content
 * exactly once; a composition in a `Popup` never asks for a copy of the Activity window.
 *
 * Mutations that turn this red (by reading): a reader that keeps one shared continuation or one
 * shared bitmap across reads; a listener that resumes without the `isActive` check and lets the
 * value through after a cancel; a loop that books or publishes in a `finally`; dropping the
 * `view.rootView !== decor` check; a draw hook that draws the content twice or not at all.
 *
 * Deterministic: Compose test clock with `autoAdvance = false`, no sleeps, no wall clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PixelCopyLoopBreakerR1Test {

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

    private class Request(
        val window: Window,
        val source: Rect,
        val destination: Bitmap,
        val listener: PixelCopy.OnPixelCopyFinishedListener,
    )

    /** Records every request and never answers on its own: the test fires each listener. */
    private class HoldingCopier : WindowCopier {
        val requests: CopyOnWriteArrayList<Request> = CopyOnWriteArrayList()

        override fun copy(
            window: Window,
            source: Rect,
            destination: Bitmap,
            listener: PixelCopy.OnPixelCopyFinishedListener,
            handler: Handler,
        ) {
            requests.add(Request(window, source, destination, listener))
        }
    }

    private val host = Host()
    private val probe = TestProbe()
    private val controller = RecordingSystemBarsController(BASE)
    private val copier = HoldingCopier()
    private val samples: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList()
    private val contentDraws = AtomicInteger()
    private var reader: PixelCopyBarStripReader? = null

    private fun setProbe(shown: () -> Boolean = { true }) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                if (shown()) {
                    val view: View = LocalView.current
                    val pixelReader: PixelCopyBarStripReader = remember(view) { PixelCopyBarStripReader(view, copier) }
                    reader = pixelReader
                    AutoSystemBarsIconStyle(
                        controller = controller,
                        probe = probe,
                        enabled = true,
                        intervalMs = INTERVAL_MS,
                        onSampled = { samples += it },
                        reader = pixelReader,
                    ) {
                        Box(Modifier.fillMaxSize().drawBehind { contentDraws.incrementAndGet() })
                    }
                }
            }
        }
        composeTestRule.runOnUiThread {
            val insets: WindowInsetsCompat = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, BAR_PX, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, BAR_PX))
                .build()
            val content: ViewGroup = composeTestRule.activity.findViewById(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), insets)
        }
        composeTestRule.waitForIdle()
    }

    private fun moveTo(state: Lifecycle.State) {
        composeTestRule.runOnUiThread { host.registry.currentState = state }
        composeTestRule.waitForIdle()
    }

    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }
    }

    private fun advance(ms: Long) {
        composeTestRule.mainClock.advanceTimeBy(ms)
        composeTestRule.waitForIdle()
    }

    private fun start() {
        moveTo(Lifecycle.State.STARTED)
        advanceFrames(FRAMES_BEFORE_FIRST_TICK)
    }

    /** Ticks one interval at a time until request [index] exists; fails when it never comes. */
    private fun awaitRequest(index: Int): Request {
        var ticks = 0
        while (copier.requests.size <= index && ticks < MAX_TICKS) {
            advance(INTERVAL_MS)
            ticks++
        }
        return assertNotNull(copier.requests.getOrNull(index), "request $index after $ticks ticks")
    }

    /** Paints the request's destination [color] and fires its listener with [code], on the main thread. */
    private fun answer(request: Request, color: Int, code: Int = PixelCopy.SUCCESS) {
        composeTestRule.runOnUiThread {
            request.destination.eraseColor(color)
            request.listener.onPixelCopyFinished(code)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a copy in flight when the host stops answers into the void and the next cycle asks again with a fresh bitmap`() {
        setProbe()
        start()
        val first: Request = assertNotNull(copier.requests.singleOrNull(), "control: the first copy is in flight")

        moveTo(Lifecycle.State.CREATED)
        answer(first, Color.WHITE)
        advance(TEN_INTERVALS_MS)

        assertEquals(BASE, controller.currentConfig, "a copy that finished after the stop published nothing")
        assertEquals(0, samples.size, "a cancelled attempt is not reported to onSampled")
        assertEquals(1, copier.requests.size, "nothing is asked while stopped")
        assertEquals(1, controller.activeOverrideCount, "the claim is kept while composed")

        start()
        val second: Request = assertNotNull(copier.requests.getOrNull(1), "the new cycle asks again")
        assertTrue(second.destination !== first.destination, "the late request may still own its bitmap")
        answer(second, Color.WHITE)

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "the new cycle's copy decided")
        assertEquals(1, samples.size)
    }

    @Test
    fun `a listener left over from an earlier cycle cannot answer the current cycle's copy`() {
        setProbe()
        start()
        val stale: Request = assertNotNull(copier.requests.singleOrNull(), "control: the first copy is in flight")
        moveTo(Lifecycle.State.CREATED)
        start()
        val current: Request = assertNotNull(copier.requests.getOrNull(1), "control: the new cycle's copy is in flight")

        answer(stale, Color.BLACK)

        assertEquals(BASE, controller.currentConfig, "the stale listener must not be published")
        assertEquals(0, samples.size, "the current read is still waiting for its own copy, not resumed by the stale one")

        answer(current, Color.WHITE)

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "only the current copy decides")
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
        assertEquals(1, samples.size)
        assertEquals(2, copier.requests.size)
    }

    @Test
    fun `leaving composition with a copy in flight releases the claim and a late listener is harmless`() {
        var shown: Boolean by mutableStateOf(true)
        setProbe { shown }
        start()
        val inFlight: Request = assertNotNull(copier.requests.singleOrNull(), "control: the copy is in flight")
        assertEquals(1, controller.activeOverrideCount, "control: the claim exists")

        composeTestRule.runOnUiThread { shown = false }
        composeTestRule.waitForIdle()
        advanceFrames(1) // the recomposition that removes the probe needs a frame on this clock
        assertEquals(0, controller.activeOverrideCount, "control: the probe left composition before the copy answers")
        answer(inFlight, Color.WHITE)
        advance(TEN_INTERVALS_MS)

        assertEquals(0, controller.activeOverrideCount, "the handle is released")
        assertEquals(BASE, controller.currentConfig)
        assertEquals(0, samples.size)
        assertEquals(1, copier.requests.size, "no read after leaving")
    }

    @Test
    fun `three failed copies after a success withdraw the claim and a later success republishes`() {
        setProbe()
        start()
        answer(copier.requests.single(), Color.WHITE)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "control: the first copy decided")

        // After the success the ticks are idle-skipped up to the idle bound; each failed copy then
        // books nothing, so the very next tick asks again.
        repeat(3) { failure ->
            val request: Request = awaitRequest(failure + 1)
            answer(request, Color.WHITE, PixelCopy.ERROR_TIMEOUT)
            if (failure < 2) {
                assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "after ${failure + 1} failures the claim holds")
            }
        }
        assertEquals(BASE, controller.currentConfig, "the third consecutive failure withdrew the claim")

        answer(awaitRequest(4), Color.WHITE)

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons, "a success republishes")
        assertEquals(5, samples.size, "one onSampled per attempt")
    }

    @Test
    fun `the window reader's draw hook draws the content exactly as often as a sibling outside the probe`() {
        val referenceDraws = AtomicInteger()
        composeTestRule.setContent {
            val view: View = LocalView.current
            val pixelReader: PixelCopyBarStripReader = remember(view) { PixelCopyBarStripReader(view, copier) }
            Box(Modifier.fillMaxSize().drawBehind { referenceDraws.incrementAndGet() })
            AutoSystemBarsIconStyle(
                controller = controller,
                probe = probe,
                enabled = true,
                intervalMs = INTERVAL_MS,
                onSampled = null,
                reader = pixelReader,
            ) {
                Box(Modifier.fillMaxSize().drawBehind { contentDraws.incrementAndGet() })
            }
        }
        composeTestRule.waitForIdle()
        val contentBefore: Int = contentDraws.get()
        val referenceBefore: Int = referenceDraws.get()

        composeTestRule.runOnUiThread {
            val decor: View = composeTestRule.activity.window.decorView
            val bitmap: Bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
        }

        val reference: Int = referenceDraws.get() - referenceBefore
        assertTrue(reference > 0, "control: the software draw reached the composition, reference draws = $reference")
        assertEquals(reference, contentDraws.get() - contentBefore, "the hook draws the content once, like any sibling")
    }

    @Test
    fun `a composition inside a Popup never asks for a copy of the Activity window`() = runTest {
        var popupView: View? = null
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize())
            Popup { popupView = capture() }
        }
        composeTestRule.waitForIdle()
        val view: View = assertNotNull(popupView, "control: the popup composed")
        assertTrue(view.rootView !== composeTestRule.activity.window.decorView, "control: the popup is its own window")
        val answering = object : WindowCopier {
            val asked = AtomicInteger()
            override fun copy(
                window: Window,
                source: Rect,
                destination: Bitmap,
                listener: PixelCopy.OnPixelCopyFinishedListener,
                handler: Handler,
            ) {
                asked.incrementAndGet()
                listener.onPixelCopyFinished(PixelCopy.SUCCESS)
            }
        }

        assertNull(PixelCopyBarStripReader(view, answering).read(BAR_PX, BAR_PX), "a popup does not draw into the Activity buffer")
        assertEquals(0, answering.asked.get())
    }

    @Composable
    private fun capture(): View = LocalView.current

    private companion object {
        val BASE: SystemBarsConfig = SystemBarsConfig.ForDarkBackground
        const val BAR_PX = 64
        const val INTERVAL_MS = 300L
        const val TEN_INTERVALS_MS = 10 * INTERVAL_MS
        const val FRAMES_BEFORE_FIRST_TICK = 3
        const val MAX_TICKS = 60
    }
}
