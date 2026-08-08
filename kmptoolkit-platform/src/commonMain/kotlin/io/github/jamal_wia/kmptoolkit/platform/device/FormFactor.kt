package io.github.jamal_wia.kmptoolkit.platform.device

/**
 * The broad shape of the device the app is running on.
 *
 * Deliberately coarse. It answers "is this a phone-sized thing or a tablet-sized thing" and
 * nothing else — it is a fact about the hardware, reported once per [DeviceInfo] instance, not a
 * layout decision. **Do not branch your UI on this**: a phone in landscape, a tablet in a small
 * multi-window pane and a foldable mid-fold all break the assumption that form factor implies
 * available width. Use window size classes for layout and keep this for the things that really
 * are hardware facts: analytics dimensions, support diagnostics, a default camera choice.
 */
public enum class FormFactor {

    /** A handset. Android: smallest screen width below 600dp. iOS: `UIUserInterfaceIdiomPhone`. */
    PHONE,

    /** A tablet. Android: smallest screen width of 600dp or more. iOS: `UIUserInterfaceIdiomPad`. */
    TABLET,

    /**
     * Something the platform did not report as either — a TV, CarPlay, a Mac running an iPad app,
     * Vision Pro, or a configuration the OS declined to describe.
     *
     * It is a real value, not an error: treat it as "assume nothing", most often by falling back
     * to whatever you do for [PHONE].
     */
    UNKNOWN,
}
