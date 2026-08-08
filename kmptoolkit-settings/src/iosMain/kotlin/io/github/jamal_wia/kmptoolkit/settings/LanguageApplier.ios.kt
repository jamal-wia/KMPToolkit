package io.github.jamal_wia.kmptoolkit.settings

import platform.Foundation.NSUserDefaults

/**
 * A [LanguageApplier] backed by the `AppleLanguages` preference.
 *
 * Takes no parameters — unlike Android, nothing on iOS needs a context to change the preferred
 * language list, which is exactly the asymmetry a shared `expect fun` would have had to hide.
 */
public fun createLanguageApplier(): LanguageApplier = IosLanguageApplier(NSUserDefaults.standardUserDefaults)

/**
 * Writes the chosen language to the top of `AppleLanguages` in the standard defaults, which is
 * where `NSBundle` looks when it resolves a localization.
 *
 * The honest caveat, which belongs in the API rather than only in the docs: `NSBundle` reads this
 * when it *creates* a bundle, and the main bundle is created before any of this runs. So the
 * change takes effect for the next launch, and for anything that builds its own bundle afterwards
 * — not for strings the running app has already resolved. There is no supported way to make a
 * running iOS app re-resolve `NSLocalizedString` against a new language; an app that wants an
 * in-place switch has to route its strings through its own locale state (Compose Multiplatform
 * resources can do that) and use this only to keep the system-level preference in step.
 *
 * @param defaults the defaults to write to. Parameterised for testing only — production always
 *   gets `standardUserDefaults`, because that is the domain `NSBundle` reads.
 */
internal class IosLanguageApplier(private val defaults: NSUserDefaults) : LanguageApplier {

    override fun apply(language: LanguageTag?) {
        if (language == null) {
            // Removing rather than writing the current system language: the key's absence is what
            // makes the app follow the system, including after the user changes it in Settings.
            defaults.removeObjectForKey(APPLE_LANGUAGES_KEY)
        } else {
            defaults.setObject(listOf(language.value), forKey = APPLE_LANGUAGES_KEY)
        }
        // synchronize() has been deprecated since iOS 12 and is called anyway: the alternative is
        // an unspecified delay before the write reaches disk, and an app that is killed by the
        // user in that window loses the language it just told them was applied.
        @Suppress("DEPRECATION")
        defaults.synchronize()
    }

    private companion object {
        const val APPLE_LANGUAGES_KEY: String = "AppleLanguages"
    }
}
