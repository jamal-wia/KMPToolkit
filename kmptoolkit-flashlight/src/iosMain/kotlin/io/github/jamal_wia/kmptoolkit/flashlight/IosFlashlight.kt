package io.github.jamal_wia.kmptoolkit.flashlight

import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceDiscoverySession
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.setTorchMode

/**
 * iOS [Flashlight] driven by the back camera's torch.
 *
 * Uses [AVCaptureDevice.lockForConfiguration] around each change, as AVFoundation requires; no
 * capture session is started, so a microphone or camera preview another part of the app is
 * holding is untouched.
 *
 * The torch needs no camera permission on iOS — only starting a capture session does.
 *
 * The blink loop is [ProcessTorch]'s, not this instance's: there is one torch per device, so every
 * instance in the process starts and stops the same pattern.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFlashlight : Flashlight {

    private val torchDevice: AVCaptureDevice?
        get() = AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
            deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
            mediaType = AVMediaTypeVideo,
            position = AVCaptureDevicePositionBack,
        ).devices
            .filterIsInstance<AVCaptureDevice>()
            .firstOrNull { device: AVCaptureDevice -> device.hasTorch }

    override val isAvailable: Boolean get() = torchDevice != null

    override fun start(pattern: FlashPattern) {
        ProcessTorch.blinkingDevice = torchDevice ?: return
        ProcessTorch.blinker.start(pattern)
    }

    override fun stop() {
        ProcessTorch.blinker.stop()
    }
}

/**
 * The one blink loop of the process, shared by every [IosFlashlight].
 *
 * The torch is a single device-wide resource, so the loop that drives it has to be single too. With
 * a loop per instance, a [Flashlight.stop] on one instance could not reach a pattern another instance
 * had started — it went dark for an instant and the other loop lit the torch again on its next
 * cycle — and two instances' patterns blinked over each other. Sharing the [TorchBlinker] gives
 * several instances the same guarantees it already gives several threads: the last `start` or `stop`
 * wins, and one `stop` ends whatever is blinking.
 */
@OptIn(ExperimentalForeignApi::class)
private object ProcessTorch {

    /**
     * The torch the blink loop switches, found once per [IosFlashlight.start] rather than on every
     * switch: a discovery session per toggle would run several times a second on a fast pattern. A
     * `stop` keeps switching the one last found, which is the torch a running pattern lit.
     */
    @Volatile
    var blinkingDevice: AVCaptureDevice? = null

    val blinker: TorchBlinker = TorchBlinker(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        setTorch = { on: Boolean -> blinkingDevice?.let { device: AVCaptureDevice -> setTorch(device, on) } },
    )

    private fun setTorch(device: AVCaptureDevice, on: Boolean) {
        val mode: Long = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (!device.isTorchModeSupported(mode)) return
        // A failed lock means something else is configuring the device; skip this cycle silently.
        if (!device.lockForConfiguration(null)) return
        device.setTorchMode(mode)
        device.unlockForConfiguration()
    }
}
