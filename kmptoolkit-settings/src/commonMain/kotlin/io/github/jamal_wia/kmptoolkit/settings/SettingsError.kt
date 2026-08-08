package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.StorageError

/**
 * Why reading or writing a setting did not do what was asked.
 *
 * These are typed causes, not messages: nothing here is meant to be shown to a user. Mapping a
 * cause onto copy in the right language is the consuming app's job — see
 * `docs/01-architecture.md`.
 *
 * They exist at all because the alternative — falling back to the default and saying nothing — is
 * the failure mode this module is most likely to hide. A settings screen that silently shows
 * "System" while the store holds "dark" and every write bounces off a broken store looks exactly
 * like a working settings screen, and the bug surfaces as "the app keeps forgetting my theme"
 * weeks later.
 */
public sealed interface SettingsError {

    /**
     * [key] could not be read at all, so the setting fell back to its configured default. Only
     * ever produced while loading — see [SettingsLoad.problems].
     *
     * The stored value, if there was one, is still there: this says the store could not be
     * reached, not that the value is gone. The very next successful write replaces it, which is
     * why the loaded default is safe to show but not safe to treat as the user's choice — see
     * `docs/kmptoolkit-settings/03-guide.md` on what to do with a load problem.
     */
    public data class ReadFailed(
        public val key: String,
        public val cause: StorageError,
    ) : SettingsError

    /**
     * [key] could not be written, so the setting was left at its previous value — the in-memory
     * [AppSettings] flow does **not** move.
     *
     * That is the deliberate half of this: publishing a value that failed to persist would show
     * the user a choice that quietly reverts at the next launch. A failed write is a state the UI
     * should reflect, not paper over.
     */
    public data class WriteFailed(
        public val key: String,
        public val cause: StorageError,
    ) : SettingsError

    /**
     * [key] held [rawValue], which is not a value this setting can take, so the setting fell back
     * to its configured default.
     *
     * Reachable in normal life, not only after tampering: a font scale written by a build whose
     * allowed range was wider, a theme mode from a version that had a fourth one, a language tag
     * that was valid until a translation was dropped, a partially restored backup. The entry is
     * left as it is — the next successful write overwrites it.
     */
    public data class UnreadableValue(
        public val key: String,
        public val rawValue: String,
    ) : SettingsError

    /**
     * [tag] is a well-formed language tag that is not in [SettingsConfig.supportedLanguages], so
     * it was refused.
     *
     * From [AppSettings.setLanguage] this means nothing was written and the current language is
     * unchanged — the caller passed a language the app has no strings for. While loading it means
     * the stored language is no longer supported, typically because an app update dropped a
     * translation, and the language fell back to [SettingsConfig.defaultLanguage].
     */
    public data class UnsupportedLanguage(public val tag: LanguageTag) : SettingsError
}
