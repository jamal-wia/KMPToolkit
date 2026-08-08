package io.github.jamal_wia.kmptoolkit.platform.device

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.posix.uname
import platform.posix.utsname

/**
 * Creates the iOS [DeviceInfo].
 *
 * Everything except [DeviceInfo.currentCountry] is read once, at construction; the country is read
 * live, since the user can change the region in Settings without restarting the app.
 *
 * `UIDevice` is documented as main-thread-only for some properties, but `systemVersion` and
 * `userInterfaceIdiom` are constants for the process lifetime and safe to read at construction
 * from any thread. Nothing here requires a permission or a usage-description string.
 */
public fun createDeviceInfo(): DeviceInfo = IosDeviceInfo()

private class IosDeviceInfo : DeviceInfo {

    override val osName: String = "iOS"

    override val osVersion: String =
        UIDevice.currentDevice.systemVersion.takeIf { it.isNotBlank() } ?: "unknown"

    /**
     * The machine identifier from `uname` — `"iPhone15,2"` — not `UIDevice.name`.
     *
     * `UIDevice.name` is user-editable ("Anna's iPhone"), which makes it personal data and, since
     * iOS 16, something the OS redacts to a generic string without an entitlement. The machine id
     * is stable, precise, and identifies the hardware rather than the person holding it.
     */
    @OptIn(ExperimentalForeignApi::class)
    override val model: String = memScoped {
        val info = alloc<utsname>()
        uname(info.ptr)
        info.machine.toKString().ifEmpty { UIDevice.currentDevice.model }
    }

    override val formFactor: FormFactor = when (UIDevice.currentDevice.userInterfaceIdiom) {
        UIUserInterfaceIdiomPhone -> FormFactor.PHONE
        UIUserInterfaceIdiomPad -> FormFactor.TABLET
        // TV, CarPlay, Mac and Vision all land here on purpose: none of them is a phone or a
        // tablet, and guessing which one they resemble would be worse than saying so.
        else -> FormFactor.UNKNOWN
    }

    override fun currentCountry(): String? = normalizeCountryCode(NSLocale.currentLocale.countryCode)
}
