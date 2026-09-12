package io.github.jamal_wia.kmptoolkit.language

/**
 * Sets the platform's default locale to [language] — `Locale.setDefault` and `LocaleList.setDefault`
 * on Android, the `AppleLanguages` array in `NSUserDefaults` on iOS, `Locale.setDefault` on the
 * desktop JVM.
 *
 * This is a global side effect: it changes what every string-resource lookup in the process resolves
 * to from this call onward, not just what runs inside a Composition. [AppLanguageHolder] calls it
 * automatically on creation and on **every** [AppLanguageHolder.setLanguage] — including one that
 * passes the language already selected, because the platform default is shared state that something
 * else in the process may have moved since — so you should not normally need to call it yourself.
 *
 * [AppLanguage.System] clears any previously applied override, so the platform's own language takes
 * over again — it does not merely leave a prior selection in place.
 */
public expect fun applyLanguageGlobally(language: AppLanguage)

/**
 * The device's own preferred language, as the platform reports it right now — independent of
 * whatever [AppLanguageHolder] currently holds, and unaffected by a prior [applyLanguageGlobally]
 * call.
 *
 * Use this to resolve an [AppLanguage.System] selection against your own supported-language list:
 * this module carries no such list to resolve against itself, only the platform's answer to "what
 * language is the OS set to".
 *
 * `null` when the platform reports no preferred language at all — not observed on Android or iOS in
 * practice, and on the desktop JVM only when the environment carries no `user.language`.
 */
public expect fun getSystemLanguageCode(): String?
