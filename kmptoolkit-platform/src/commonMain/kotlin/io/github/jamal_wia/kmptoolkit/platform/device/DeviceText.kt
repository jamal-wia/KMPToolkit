package io.github.jamal_wia.kmptoolkit.platform.device

/**
 * Normalizes a raw region code from the platform into the [DeviceInfo.currentCountry] contract:
 * exactly two ASCII letters, uppercased, or `null`.
 *
 * Rejecting anything else is deliberate. Both platforms can hand back values that are not alpha-2
 * region codes — an empty string when no region is set, a three-digit UN M.49 code such as `"419"`
 * (Latin America) from a locale like `es-419`, or a longer subtag. Passing those through would put
 * a value into a header or an analytics dimension that claims to be ISO 3166-1 and is not.
 */
internal fun normalizeCountryCode(raw: String?): String? =
    raw?.takeIf { code -> code.length == 2 && code.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' } }
        ?.uppercase()

/**
 * Composes [DeviceInfo.model] from Android's manufacturer and model strings.
 *
 * The manufacturer is prefixed only when the model does not already start with it: some OEMs put
 * their own name in `Build.MODEL` and the naive concatenation produces `"Samsung Samsung SM-G991B"`.
 * Returns `"unknown"` rather than an empty string when the platform reports neither, so that a
 * consumer never has to decide what an empty model means.
 */
internal fun composeDeviceModel(manufacturer: String?, model: String?): String {
    val cleanManufacturer: String = manufacturer.orEmpty().trim()
    val cleanModel: String = model.orEmpty().trim()
    return when {
        cleanModel.isEmpty() -> cleanManufacturer.ifEmpty { "unknown" }
        cleanManufacturer.isEmpty() -> cleanModel
        cleanModel.startsWith(cleanManufacturer, ignoreCase = true) -> cleanModel
        else -> "${cleanManufacturer.replaceFirstChar { ch -> ch.uppercaseChar() }} $cleanModel"
    }
}
