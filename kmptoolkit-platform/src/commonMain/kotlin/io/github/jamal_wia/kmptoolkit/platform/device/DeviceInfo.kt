package io.github.jamal_wia.kmptoolkit.platform.device

/**
 * Static facts about the device and OS, in a form shared code can read.
 *
 * The typical use is diagnostic: request headers for support triage, a crash report footer, an
 * analytics dimension, a "tell us about your device" screen. Obtain an instance from the platform
 * factory (`createDeviceInfo(context)` on Android, `createDeviceInfo()` on iOS) and pass it into
 * shared code as this interface.
 *
 * Everything here is a plain `String` rather than a parsed type. [osVersion] in particular is
 * **not** guaranteed to be a dotted numeric version — Android preview releases report names like
 * `"VanillaIceCream"` — so compare it as an opaque token or not at all, and never `toInt()` it.
 *
 * No value identifies the *user* or the *install*: there is no advertising id, no vendor id, no
 * generated device id. Those carry privacy and store-policy obligations that belong to the app,
 * not to a library it happens to depend on.
 */
public interface DeviceInfo {

    /** The OS family: `"Android"` or `"iOS"`. A stable token, safe to compare against. */
    public val osName: String

    /**
     * The user-visible OS version — `"14"` on Android, `"17.4"` on iOS.
     *
     * Falls back to the API level as a string on an Android build that reports no release name,
     * and is never empty.
     */
    public val osVersion: String

    /**
     * The device model — `"Google Pixel 7"` on Android, `"iPhone15,2"` on iOS.
     *
     * Android composes it from manufacturer and model, skipping the manufacturer when the model
     * already starts with it. iOS reports the machine identifier rather than the device *name*,
     * which the user can edit and which is personal data. Never empty: `"unknown"` when the
     * platform reports nothing.
     */
    public val model: String

    /** The device's broad shape — see [FormFactor] before branching on it. */
    public val formFactor: FormFactor

    /**
     * The device's current region as an uppercase ISO 3166-1 alpha-2 code (`"US"`, `"UZ"`), or
     * `null` when the device reports no region or reports something that is not a two-letter code.
     *
     * A function, not a property, because it is read live on every call: the user can change the
     * region in Settings while the app is running, and a value cached at construction would then
     * be silently wrong for the rest of the process. It is the *device's* region, which is not the
     * user's nationality, not their current location, and not their app language.
     */
    public fun currentCountry(): String?
}
