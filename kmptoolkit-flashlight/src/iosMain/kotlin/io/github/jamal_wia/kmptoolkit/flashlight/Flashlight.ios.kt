package io.github.jamal_wia.kmptoolkit.flashlight

/**
 * Creates the iOS [Flashlight], on top of the back camera's `AVCaptureDevice` torch.
 *
 * No permission, entitlement, or `Info.plist` entry is involved on iOS. There is also nothing to
 * release: the instance holds no capture session and no device reference beyond what it looks up
 * fresh on every [Flashlight.start] and [Flashlight.stop] call.
 */
public fun createFlashlight(): Flashlight = IosFlashlight()
