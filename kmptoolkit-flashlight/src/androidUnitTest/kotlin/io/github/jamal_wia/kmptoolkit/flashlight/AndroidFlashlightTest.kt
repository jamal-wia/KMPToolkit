package io.github.jamal_wia.kmptoolkit.flashlight

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The torch on Android, and the two facts about it that decide whether the cue works at all.
 *
 * A device with a camera and no flash unit must say so up front rather than blink into the void,
 * because the caller falls back to another cue on that answer. And whatever happens while
 * blinking — a stop, a cancellation, another app grabbing the camera — the torch must end up OFF: a
 * light left burning is the one failure nobody would forgive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AndroidFlashlightTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val cameraManager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun addCamera(id: String, hasFlash: Boolean) {
        val characteristics: CameraCharacteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowCharacteristics: ShadowCameraCharacteristics = Shadow.extract(characteristics)
        shadowCharacteristics.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, hasFlash)
        val shadowManager: ShadowCameraManager = shadowOf(cameraManager)
        shadowManager.addCamera(id, characteristics)
    }

    @Test
    fun `a device with a flash unit reports the torch as available`() {
        addCamera(id = "0", hasFlash = true)

        assertTrue(AndroidFlashlight(context).isAvailable)
    }

    @Test
    fun `a camera without a flash unit is not a torch`() {
        addCamera(id = "0", hasFlash = false)

        assertFalse(AndroidFlashlight(context).isAvailable)
    }

    @Test
    fun `a second camera with no flash does not stand in for a missing one`() {
        addCamera(id = "0", hasFlash = false)
        addCamera(id = "1", hasFlash = false)

        assertFalse(AndroidFlashlight(context).isAvailable)
    }

    @Test
    fun `a device with no camera at all is not a torch`() {
        assertFalse(AndroidFlashlight(context).isAvailable)
    }

    @Test
    fun `start lights the torch`() {
        addCamera(id = "0", hasFlash = true)
        val flashlight = AndroidFlashlight(context)

        flashlight.start(FlashPattern.Blink)
        awaitTorch(expected = true)

        assertEquals(true, torchMode())
        flashlight.stop()
    }

    @Test
    fun `stop leaves the torch off`() {
        addCamera(id = "0", hasFlash = true)
        val flashlight = AndroidFlashlight(context)
        flashlight.start(FlashPattern.Blink)
        awaitTorch(expected = true)

        flashlight.stop()
        awaitTorch(expected = false)

        assertEquals(false, torchMode())
    }

    @Test
    fun `starting twice does not leave a second pattern running`() {
        // A caller may re-arm freely; the second start replaces the first rather than stacking, so
        // one stop is still enough to go dark.
        addCamera(id = "0", hasFlash = true)
        val flashlight = AndroidFlashlight(context)

        flashlight.start(FlashPattern.Blink)
        flashlight.start(FlashPattern.Attention)
        awaitTorch(expected = true)
        flashlight.stop()
        awaitTorch(expected = false)

        assertEquals(false, torchMode())
    }

    @Test
    fun `a device without a torch survives start and stop`() {
        // No flash unit: every call is a no-op, and none of them may throw into the caller.
        addCamera(id = "0", hasFlash = false)
        val flashlight = AndroidFlashlight(context)

        flashlight.start(FlashPattern.Blink)
        flashlight.stop()
    }

    /**
     * The blink runs on a real dispatcher, so the assertion waits for the actual toggle rather
     * than running on virtual time — under `runTest` the delays would be skipped and nothing
     * observed.
     */
    private fun awaitTorch(expected: Boolean) {
        repeat(TORCH_POLL_ATTEMPTS) {
            if (torchMode() == expected) return
            Thread.sleep(TORCH_POLL_INTERVAL_MS)
        }
    }

    /**
     * The torch as the platform shadow sees it, or `null` while it has never been set —
     * Robolectric keeps no default and throws on a read before the first write.
     */
    private fun torchMode(): Boolean? = runCatching { shadowOf(cameraManager).getTorchMode("0") }.getOrNull()

    private companion object {
        const val TORCH_POLL_ATTEMPTS = 100
        const val TORCH_POLL_INTERVAL_MS = 10L
    }
}
