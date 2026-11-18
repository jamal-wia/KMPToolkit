package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop actuals of the module (docs/kmptoolkit-systembars/05-platform-notes.md: a desktop window has no
 * system bars, the controller still tracks a configuration and behaves like the headless one, the wake lock is a
 * no-op). Nothing exercised them before: the commonTest classes use a recording fake.
 */
class DesktopNoOpsTest {

    private val dark: SystemBarsOverride = SystemBarsOverride.icons(SystemBarIconStyle.LightIcons)

    @Test
    fun `the desktop controller starts at the given configuration and follows the layer stack`() {
        val controller: SystemBarsController = createSystemBarsController(SystemBarsConfig.ForLightBackground)
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(dark)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons)
        handle.release()
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        controller.release()
        assertEquals(SystemBarsConfig(), controller.currentConfig, "release resets the base to the defaults")
    }

    @Test
    fun `a thousand claim and release cycles from eight threads end on the base`() {
        val controller: SystemBarsController = createSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val done: CountDownLatch = CountDownLatch(8)
        val failures: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())
        repeat(8) {
            Thread {
                try {
                    repeat(1000) {
                        val handle: SystemBarsOverrideHandle = controller.applyOverride(dark)
                        handle.update(SystemBarsOverride.None)
                        handle.release()
                        handle.release()
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish within the hang bound")
        assertEquals(emptyList(), failures)
        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
    }

    @Test
    fun `the desktop wake lock accepts any sequence of calls and never throws`() {
        val lock: ScreenWakeLockController = createScreenWakeLockController()
        repeat(1000) { n ->
            lock.setKeepScreenOn(n % 3 != 0)
            lock.setKeepScreenOn(n % 2 == 0)
        }
        lock.setKeepScreenOn(false)
    }

    /**
     * `AutoSystemBarsIconStyle` on the desktop (plan issue-13 section 6.8): there is no reader, so the
     * probe composes its content, never samples and never claims the controller. A desktop window has
     * no bar insets either; the null reader says so explicitly.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the desktop probe has no reader, composes its content and makes no claim`() = runComposeUiTest {
        val claims: AtomicInteger = AtomicInteger()
        val inner: SystemBarsController = createSystemBarsController(SystemBarsConfig.ForLightBackground)
        val controller: SystemBarsController = object : SystemBarsController by inner {
            override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle {
                claims.incrementAndGet()
                return inner.applyOverride(override)
            }
        }
        var reader: BarStripReader? = null
        var composed = 0
        setContent {
            reader = rememberBarStripReader()
            AutoSystemBarsIconStyle(controller, createStatusBarLuminanceProbe()) {
                Box { composed++ }
            }
        }
        waitForIdle()

        assertNull(reader, "the desktop has no pixel source")
        assertTrue(composed > 0, "the content composed")
        assertEquals(0, claims.get(), "no claim was pushed")
        assertEquals(SystemBarsConfig.ForLightBackground, inner.currentConfig)
    }
}
