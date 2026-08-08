package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.device.DeviceInfo
import io.github.jamal_wia.kmptoolkit.platform.device.FormFactor

/**
 * A [DeviceInfo] with every value fixed by the test.
 *
 * Every parameter has a default, so a test names only what it cares about:
 *
 * ```kotlin
 * val info = FakeDeviceInfo(formFactor = FormFactor.TABLET, country = null)
 * ```
 *
 * @param osName see [DeviceInfo.osName].
 * @param osVersion see [DeviceInfo.osVersion].
 * @param model see [DeviceInfo.model].
 * @param formFactor see [DeviceInfo.formFactor].
 * @param country what [DeviceInfo.currentCountry] returns. `null` models a device with no region
 *   set — the case most code forgets, which is why it is a parameter rather than always a value.
 */
public class FakeDeviceInfo(
    override val osName: String = "FakeOS",
    override val osVersion: String = "1.0",
    override val model: String = "FakeDevice",
    override val formFactor: FormFactor = FormFactor.PHONE,
    country: String? = "US",
) : DeviceInfo {

    /**
     * The value [currentCountry] returns.
     *
     * Mutable, because a real device's region can change while the process runs and code that
     * caches it is exactly what a test should be able to catch.
     */
    public var country: String? = country

    override fun currentCountry(): String? = country
}
