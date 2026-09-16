package io.github.jamal_wia.kmptoolkit.hijri

/**
 * Runs [block] as if the device were set to [zoneId], then puts the previous zone back even when
 * [block] throws.
 *
 * "As if the device were set" means every default a conversion could read by mistake: the platform's
 * own default zone and the one `kotlinx.datetime.TimeZone.currentSystemDefault()` answers. Changing
 * only one of them would let a regression through that reads the other.
 */
internal expect fun withDeviceTimeZone(zoneId: String, block: () -> Unit)
