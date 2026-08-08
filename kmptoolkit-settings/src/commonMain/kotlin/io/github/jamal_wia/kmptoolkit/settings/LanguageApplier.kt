package io.github.jamal_wia.kmptoolkit.settings

/**
 * Applies a chosen [LanguageTag] as the language the platform resolves strings in, process-wide.
 *
 * Separate from [AppSettings] because the two do genuinely different things: [AppSettings] records
 * what the user picked, this makes the running app speak it. Keeping them apart means a stored
 * preference never triggers a process-wide side effect on its own, and an app that resolves
 * strings itself — a server-driven catalogue, a Compose Resources setup with its own locale state
 * — can use the preference without this at all. Wiring them together is three lines, in
 * `docs/kmptoolkit-settings/03-guide.md`.
 *
 * **What "applies" means differs sharply between the platforms, and neither is instant.** Read
 * `docs/kmptoolkit-settings/05-platform-notes.md` before deciding what your UI does after calling
 * this — on Android 13+ the system restarts your activities for you, below that it takes effect
 * for resources loaded afterwards, and on iOS an already-running app generally keeps the strings
 * it has until it is restarted. The short version:
 *
 * | | Android 13+ (API 33) | Android 12 and below | iOS |
 * |---|---|---|---|
 * | Mechanism | `LocaleManager.setApplicationLocales` | `Locale`/`LocaleList` defaults | `AppleLanguages` in `NSUserDefaults` |
 * | Persisted by the platform | yes | no — by [AppSettings] only | yes |
 * | Visible in the running app | activities are recreated | for resources loaded afterwards | usually after a restart |
 *
 * Obtain one from the platform factory — `createLanguageApplier(context)` on Android,
 * `createLanguageApplier()` on iOS — and pass this interface into shared code; shared code never
 * names the factory. It is a `fun interface`, so a test substitutes a lambda.
 */
public fun interface LanguageApplier {

    /**
     * Makes [language] the language the platform resolves strings in, or hands the choice back to
     * the operating system when it is `null`.
     *
     * Idempotent and safe to call with the value that is already applied — every backend either
     * no-ops or rewrites the same value. Never throws: a platform that will not take the language
     * leaves the current one in place. It returns nothing for the same reason, and it is the one
     * place in this module where an outcome is not reported — none of the three platform APIs
     * reports whether the language was accepted, so a [SettingsResult] here would be an invented
     * `Success` on every call.
     */
    public fun apply(language: LanguageTag?)
}
