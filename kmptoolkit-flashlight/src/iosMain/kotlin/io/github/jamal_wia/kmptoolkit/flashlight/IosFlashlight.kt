package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var blinking: Job? = null

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
        val device: AVCaptureDevice = torchDevice ?: return
        blinking?.cancel()
        blinking = scope.launch {
            try {
                while (isActive) {
                    setTorch(device, on = true)
                    delay(pattern.on)
                    setTorch(device, on = false)
                    delay(pattern.off)
                }
            } finally {
                setTorch(device, on = false)
            }
        }
    }

    override fun stop() {
        blinking?.cancel()
        blinking = null
        torchDevice?.let { device: AVCaptureDevice -> setTorch(device, on = false) }
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
