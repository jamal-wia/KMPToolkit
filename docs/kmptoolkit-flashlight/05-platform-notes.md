# kmptoolkit-flashlight — Platform notes

What differs behind `Flashlight`, and the one thing that is refreshingly simple compared to most
hardware-backed modules in this suite: there is nothing to declare.

## Permissions

### Android — none

Torch mode is reached through `CameraManager.setTorchMode(cameraId, enable)`, which needs **no
permission at all** — not even `android.permission.CAMERA`. That permission gates opening a
capture session (`CameraDevice`, previews, photos, video); this module never opens one. There is
nothing to add to your manifest, and nothing this library declares in its own.

### iOS — none

`AVCaptureDevice`'s torch is reached without a capture session either. iOS gates camera access
(`NSCameraUsageDescription`, the `AVCaptureDevice.authorizationStatus` prompt) behind *starting a
capture session* — again, something this module never does. No `Info.plist` entry, no user
consent dialog.

**Practical consequence:** unlike `kmptoolkit-haptics` (`VIBRATE`) or `kmptoolkit-permission`'s
catalog, there is no missing-declaration failure mode to document here, because there is no
declaration to forget.

## Android

### Finding the torch

```kotlin
manager.cameraIdList.firstOrNull { cameraId ->
    manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
}
```

The first camera reporting a flash unit wins — usually the back camera, but the module does not
assume a lens position, only the characteristic. Resolution happens once, at construction:
`isAvailable` is `torchCameraId != null` for the rest of the instance's lifetime.

**A rear camera with no flash unit is the common case this module is built to degrade
gracefully on**, not an edge case: plenty of tablets have a camera and no flash.

### The blink loop

```kotlin
while (isActive) {
    setTorch(cameraId, on = true)
    delay(pattern.on)
    setTorch(cameraId, on = false)
    delay(pattern.off)
}
```

Runs on `Dispatchers.Default` inside a `SupervisorJob`-scoped coroutine started by `start`. The
loop's `finally` block turns the torch off unconditionally, so cancellation landing mid-cycle — a
`stop()` call, or a second `start()` replacing the pattern — can never leave the torch lit.

### Failure handling

`CameraManager.setTorchMode` can throw:

| Exception | Cause | Handling |
|---|---|---|
| `CameraAccessException` | Another app holds the camera, or the system refuses the request | Swallowed at the call site; that blink cycle is silently skipped |
| `IllegalArgumentException` | The camera id stopped being valid (a hot-unplugged external camera) | Swallowed the same way |

Both are caught inside the private `setTorch` helper, on every call, not just the first — so a
camera regained mid-blink resumes flashing on its own on the next cycle.

## iOS

### Finding the torch

```kotlin
AVCaptureDeviceDiscoverySession.discoverySessionWithDeviceTypes(
    deviceTypes = listOf(AVCaptureDeviceTypeBuiltInWideAngleCamera),
    mediaType = AVMediaTypeVideo,
    position = AVCaptureDevicePositionBack,
).devices.filterIsInstance<AVCaptureDevice>().firstOrNull { it.hasTorch }
```

Unlike the Android implementation, this lookup runs on every `start` and `stop` rather than being
cached at construction — `AVCaptureDevice` instances are not meant to be held across configuration
changes, and re-discovering is cheap. The practical behavior is identical: `isAvailable` reflects
whatever the device reports right now, and a device without a back wide-angle camera with a torch
(the iOS Simulator, always) reports `false`.

### The blink loop

Structurally identical to Android's: a `SupervisorJob`-scoped coroutine on `Dispatchers.Default`,
a `finally` that turns the torch off unconditionally, replaced rather than stacked on a second
`start`.

### `lockForConfiguration`

AVFoundation requires exclusive configuration access around a torch change:

```kotlin
if (!device.isTorchModeSupported(mode)) return
if (!device.lockForConfiguration(null)) return
device.setTorchMode(mode)
device.unlockForConfiguration()
```

A failed lock means something else is configuring the device at that instant — the cycle is
skipped silently, exactly like a swallowed `CameraAccessException` on Android, and the next cycle
tries again.

## Behavior identical on both platforms

No permission, no capture session, no manifest or `Info.plist` entry. Both implementations blink
on their own coroutine, replace rather than stack a second `start`, and guarantee the torch is off
once `stop` runs or the blink job is cancelled. Neither platform reports back whether the LED
physically lit — `isAvailable` is the only synchronous fact either one can offer.
