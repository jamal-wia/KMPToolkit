package io.github.jamal_wia.kmptoolkit.settings

/**
 * What an [AppSettings] stores its three values under, which languages it accepts, and what each
 * setting falls back to when nothing usable is stored.
 *
 * Fixed at construction. Changing a default later changes what a *fresh install* sees, not what
 * users who already chose something see — that is the point of persisting the choice.
 *
 * ```kotlin
 * val config = SettingsConfig(
 *     supportedLanguages = setOf(LanguageTag("en"), LanguageTag("pt-BR")),
 *     defaultLanguage = LanguageTag("en"),
 * )
 * ```
 *
 * @param keyPrefix what the three storage keys start with — they are [fontScaleKey],
 *   [themeModeKey] and [languageKey], each this prefix plus a suffix. The default is this
 *   module's own package name, which is a namespace nothing else has a reason to write into; it
 *   is not the consuming app's identifier, because the store this module writes to is already
 *   scoped to the app by `StorageConfig`. Override it to keep two independent settings stores
 *   inside one [io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage], or to keep reading keys
 *   an earlier version of your app wrote. Must not be blank.
 * @param supportedLanguages the languages the app actually has strings for. Empty — the default —
 *   means "accept any well-formed tag", which is the right setting for an app that resolves
 *   languages dynamically and the wrong one for an app with a fixed translation catalogue: with a
 *   non-empty set, a stored language that is no longer supported falls back to [defaultLanguage]
 *   instead of leaving the app in a language it has no strings for, and
 *   [AppSettings.setLanguage] refuses a tag outside the set instead of persisting it.
 * @param defaultFontScale the scale a fresh install renders at. Rarely anything but
 *   [FontScale.DEFAULT] — an app whose baseline is not its own design size has a typography
 *   problem rather than a settings one.
 * @param defaultThemeMode the mode a fresh install uses. [ThemeMode.SYSTEM] by default, so a user
 *   who never opens the settings screen gets the appearance they already chose system-wide.
 * @param defaultLanguage the language a fresh install runs in. `null` — the default — means
 *   "follow the operating system", which is what an app with a translation for the user's system
 *   language should do. A non-null value must be in [supportedLanguages] when that set is
 *   non-empty.
 */
public data class SettingsConfig(
    public val keyPrefix: String = DEFAULT_KEY_PREFIX,
    public val supportedLanguages: Set<LanguageTag> = emptySet(),
    public val defaultFontScale: FontScale = FontScale.DEFAULT,
    public val defaultThemeMode: ThemeMode = ThemeMode.SYSTEM,
    public val defaultLanguage: LanguageTag? = null,
) {
    init {
        // Validated here rather than reported as a SettingsError: every one of these is written as
        // a literal at a call site, so a wrong one is a bug to fix there, not a runtime condition
        // an app can recover from.
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
        require(
            defaultLanguage == null ||
                supportedLanguages.isEmpty() ||
                defaultLanguage in supportedLanguages,
        ) {
            "defaultLanguage $defaultLanguage must be one of supportedLanguages " +
                "$supportedLanguages, or the fallback would be a language the app cannot render"
        }
    }

    /** Where [AppSettings.fontScale] is stored: [keyPrefix] + `".font_scale"`. */
    public val fontScaleKey: String get() = "$keyPrefix.font_scale"

    /** Where [AppSettings.themeMode] is stored: [keyPrefix] + `".theme_mode"`. */
    public val themeModeKey: String get() = "$keyPrefix.theme_mode"

    /** Where [AppSettings.language] is stored: [keyPrefix] + `".language"`. */
    public val languageKey: String get() = "$keyPrefix.language"

    /**
     * Whether [tag] is one this configuration accepts — always true when [supportedLanguages] is
     * empty, membership otherwise.
     */
    internal fun supports(tag: LanguageTag): Boolean =
        supportedLanguages.isEmpty() || tag in supportedLanguages

    public companion object {

        /** `"io.github.jamal_wia.kmptoolkit.settings"` — this module's own package name. */
        public const val DEFAULT_KEY_PREFIX: String = "io.github.jamal_wia.kmptoolkit.settings"
    }
}
