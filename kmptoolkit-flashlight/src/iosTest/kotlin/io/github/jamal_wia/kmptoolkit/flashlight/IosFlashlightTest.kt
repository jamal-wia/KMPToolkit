package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The iOS Simulator has no camera hardware, so it is the one guaranteed "no torch" device this
 * suite can exercise without a physical iPhone — the same role the cheap-tablet case plays in
 * [AndroidFlashlightTest] on the JVM side. What is worth pinning here is exactly what that case
 * pins there: the module says up front that there is no torch, and every call survives regardless.
 *
 * The actual blinking — the `lockForConfiguration` dance and the on/off timing — needs a device
 * with a torch to observe and is not exercised by this suite; [IosFlashlight]'s KDoc documents the
 * behavior, and it mirrors [AndroidFlashlight]'s, which the Robolectric suite does cover.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFlashlightTest {

    @Test
    fun `the simulator reports no torch`() {
        assertFalse(IosFlashlight().isAvailable)
    }

    @Test
    fun `start and stop survive a device with no torch`() {
        val flashlight = IosFlashlight()

        flashlight.start(FlashPattern.Blink)
        flashlight.stop()
    }

    @Test
    fun `re-arming with a second start still survives a device with no torch`() {
        val flashlight = IosFlashlight()

        flashlight.start(FlashPattern.Blink)
        flashlight.start(FlashPattern.Attention)
        flashlight.stop()
    }

    @Test
    fun `stop before any start is a safe no-op`() {
        IosFlashlight().stop()
    }
}
