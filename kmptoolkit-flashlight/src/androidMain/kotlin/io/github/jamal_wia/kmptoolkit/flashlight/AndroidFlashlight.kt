package io.github.jamal_wia.kmptoolkit.flashlight

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.Volatile

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
 *
 * The blink loop is [ProcessTorch]'s, not this instance's: there is one torch per device, so every
 * instance in the process starts and stops the same pattern.
 */
internal class AndroidFlashlight(
    context: Context,
) : Flashlight {

    private val cameraManager: CameraManager? =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /** The first camera that actually has a flash unit — absent on many tablets. */
    private val torchCameraId: String? = resolveTorchCameraId()

    override val isAvailable: Boolean get() = torchCameraId != null

    override fun start(pattern: FlashPattern) {
        val cameraId: String = torchCameraId ?: return
        val manager: CameraManager = cameraManager ?: return
        ProcessTorch.target = TorchTarget(manager, cameraId)
        ProcessTorch.blinker.start(pattern)
    }

    override fun stop() {
        ProcessTorch.blinker.stop()
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

/** The torch a pattern switches: which camera, through which manager. */
private class TorchTarget(val manager: CameraManager, val cameraId: String)

/**
 * The one blink loop of the process, shared by every [AndroidFlashlight].
 *
 * The torch is a single device-wide resource, so the loop that drives it has to be single too. With
 * a loop per instance, a [Flashlight.stop] on one instance could not reach a pattern another instance
 * had started — it went dark for an instant and the other loop lit the torch again on its next
 * cycle — and two instances' patterns blinked over each other. Sharing the [TorchBlinker] gives
 * several instances the same guarantees it already gives several threads: the last `start` or `stop`
 * wins, and one `stop` ends whatever is blinking.
 */
private object ProcessTorch {

    /**
     * What the loop switches — set by each [AndroidFlashlight.start] before it starts its pattern. A
     * `stop` keeps switching the one last set, which is the torch a running pattern lit.
     */
    @Volatile
    var target: TorchTarget? = null

    val blinker: TorchBlinker = TorchBlinker(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        setTorch = { on: Boolean -> target?.let { torch: TorchTarget -> setTorch(torch, on) } },
    )

    private fun setTorch(torch: TorchTarget, on: Boolean) {
        try {
            torch.manager.setTorchMode(torch.cameraId, on)
        } catch (_: CameraAccessException) {
            // Another app has the camera, or the system refused. Nothing to recover — stay quiet.
        } catch (_: IllegalArgumentException) {
            // The id stopped being valid (a hot-unplugged external camera). Same.
        }
    }
}
