package io.github.jamal_wia.kmptoolkit.flashlight

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android [Flashlight] driven by [CameraManager.setTorchMode].
 *
 * Torch mode needs **no permission** — not even `CAMERA` — and opens no capture session, so it
 * does not disturb a microphone or camera preview another part of the app is holding at the same
 * time.
 *
 * Every platform call is wrapped: [CameraManager.setTorchMode] throws [CameraAccessException]
 * when another app holds the camera, when the torch is already in use, or when the device is in a
 * state that forbids it. None of that is worth an exception to the caller — the torch is a
 * best-effort cue, and losing it silently is the designed behavior.
 */
internal class AndroidFlashlight(
    context: Context,
) : Flashlight {

    private val cameraManager: CameraManager? =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** The first camera that actually has a flash unit — absent on many tablets. */
    private val torchCameraId: String? = resolveTorchCameraId()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var blinking: Job? = null

    override val isAvailable: Boolean get() = torchCameraId != null

    override fun start(pattern: FlashPattern) {
        val cameraId: String = torchCameraId ?: return
        // A second start replaces the running pattern rather than layering a second blink on top.
        blinking?.cancel()
        blinking = scope.launch {
            try {
                while (isActive) {
                    setTorch(cameraId, on = true)
                    delay(pattern.on)
                    setTorch(cameraId, on = false)
                    delay(pattern.off)
                }
            } finally {
                // Cancellation lands mid-cycle as often as not; the torch must never be left
                // burning.
                setTorch(cameraId, on = false)
            }
        }
    }

    override fun stop() {
        blinking?.cancel()
        blinking = null
        torchCameraId?.let { cameraId: String -> setTorch(cameraId, on = false) }
    }

    private fun setTorch(cameraId: String, on: Boolean) {
        try {
            cameraManager?.setTorchMode(cameraId, on)
        } catch (_: CameraAccessException) {
            // Another app has the camera, or the system refused. Nothing to recover — stay quiet.
        } catch (_: IllegalArgumentException) {
            // The id stopped being valid (a hot-unplugged external camera). Same.
        }
    }

    private fun resolveTorchCameraId(): String? {
        val manager: CameraManager = cameraManager ?: return null
        return try {
            manager.cameraIdList.firstOrNull { cameraId: String ->
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: CameraAccessException) {
            null
        }
    }
}
