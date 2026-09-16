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

    /**
     * The torch the blink loop switches, found once per [start] rather than on every switch: a
     * discovery session per toggle would run several times a second on a fast pattern. [stop] keeps
     * switching the one last found, which is the torch a running pattern lit.
     */
    @Volatile
    private var blinkingDevice: AVCaptureDevice? = null

    private val blinker: TorchBlinker = TorchBlinker(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        setTorch = { on: Boolean -> blinkingDevice?.let { device: AVCaptureDevice -> setTorch(device, on) } },
    )

    override fun start(pattern: FlashPattern) {
        blinkingDevice = torchDevice ?: return
        blinker.start(pattern)
    }

    override fun stop() {
        blinker.stop()
    }

    private fun setTorch(device: AVCaptureDevice, on: Boolean) {
        val mode: Long = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (!device.isTorchModeSupported(mode)) return
        // A failed lock means something else is configuring the device; skip this cycle silently.
        if (!device.lockForConfiguration(null)) return
        device.setTorchMode(mode)
        device.unlockForConfiguration()
    }
}
