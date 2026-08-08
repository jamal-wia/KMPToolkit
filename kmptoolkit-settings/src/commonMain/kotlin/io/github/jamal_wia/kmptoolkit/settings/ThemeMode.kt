package io.github.jamal_wia.kmptoolkit.settings

/**
 * Which colour scheme the app renders in.
 *
 * A closed enum, unlike [FontScale] and [LanguageTag], because this one genuinely is closed:
 * every platform offers exactly light, dark, and "whatever the system says", and an app with a
 * fourth mode has a theme picker rather than a theme *mode*.
 *
 * Applying it is the consuming app's job — see `docs/kmptoolkit-settings/03-guide.md`. The only
 * subtlety is that [SYSTEM] must keep following the system: resolve it against
 * `isSystemInDarkTheme()` at render time rather than resolving it once into [LIGHT] or [DARK] and
 * storing that, or a user who picked "system" stops tracking the system the moment they restart.
 */
public enum class ThemeMode {

    /** Follow the operating system's own light/dark setting. The default for a fresh install. */
    SYSTEM,

    /** Always light, whatever the system is set to. */
    LIGHT,

    /** Always dark, whatever the system is set to. */
    DARK,
}
