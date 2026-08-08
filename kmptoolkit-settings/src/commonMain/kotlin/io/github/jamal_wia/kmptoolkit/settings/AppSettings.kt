package io.github.jamal_wia.kmptoolkit.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * The three display preferences an app is expected to let a user change — how large the text is,
 * which colour scheme it uses, and which language it speaks — persisted across restarts and
 * exposed as flows the UI can collect.
 *
 * ## Reading
 *
 * Each setting is a [StateFlow] holding the current value, populated once when the instance is
 * created and updated by this instance's own setters. It is **not** a live view of the store: a
 * second [AppSettings] over the same storage, or another process, will not push a change here.
 * One instance per app, held for the app's lifetime, is the intended shape — see
 * `docs/kmptoolkit-settings/02-getting-started.md`.
 *
 * ## Writing
 *
 * Every setter persists first and publishes second. A write that fails does **not** move the flow
 * and returns [SettingsResult.Failure] carrying a [SettingsError.WriteFailed] — the alternative,
 * showing the user a choice that silently reverts at the next launch, is a worse outcome than a
 * settings screen that can say the change did not stick.
 *
 * Setting a value that is already the current one is a no-op: it returns
 * [SettingsResult.Success] and touches neither the store nor the flow.
 *
 * None of the setters suspend. They persist through
 * [io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage], whose writes are in-memory commits
 * flushed by the platform (`SharedPreferences.apply`, `NSUserDefaults`), so this is not the
 * filesystem work that would make a suspending signature meaningful — see
 * `docs/kmptoolkit-settings/04-api-reference.md`.
 *
 * ## Threading
 *
 * Safe to read from anywhere: the flows are `StateFlow`s. Safe to write from anywhere in the sense
 * that no call corrupts anything and each individual write either lands whole or fails whole.
 *
 * What is **not** guaranteed is which of two concurrent writes to the *same* setting wins: the
 * store and the flow are two separate steps, so a sufficiently unlucky interleaving can leave the
 * flow holding one of the two values and the store the other, and the disagreement surfaces at the
 * next launch. Two concurrent writes to two *different* settings never interfere. If a setting can
 * be changed from more than one place at once — a settings screen and a deep link, say — drive the
 * writes from a single dispatcher.
 */
public interface AppSettings {

    /**
     * How much larger or smaller than its designed size type should be rendered. Starts at the
     * stored value, or [SettingsConfig.defaultFontScale] when nothing usable is stored.
     */
    public val fontScale: StateFlow<FontScale>

    /**
     * The colour scheme the app should render in. Starts at the stored value, or
     * [SettingsConfig.defaultThemeMode] when nothing usable is stored.
     *
     * [ThemeMode.SYSTEM] means "resolve against the system at render time" — it is not resolved
     * into a concrete scheme here, because this module holds state and does not render.
     */
    public val themeMode: StateFlow<ThemeMode>

    /**
     * The language the app should be displayed in, or `null` to follow the operating system.
     * Starts at the stored value, or [SettingsConfig.defaultLanguage] when nothing usable is
     * stored.
     *
     * Changing this value does not itself change the language anything is rendered in — that is
     * [LanguageApplier]'s job, and wiring the two together is three lines shown in
     * `docs/kmptoolkit-settings/03-guide.md`. They are kept apart because applying a language is a
     * process-wide side effect with platform-specific timing, and a *stored preference* should not
     * silently trigger one.
     */
    public val language: StateFlow<LanguageTag?>

    /**
     * Persists [value] as the font scale and publishes it to [fontScale].
     *
     * @return [SettingsResult.Failure] with [SettingsError.WriteFailed] when the store rejected
     *   the write, in which case [fontScale] is unchanged.
     */
    public fun setFontScale(value: FontScale): SettingsResult

    /**
     * Persists [value] as the theme mode and publishes it to [themeMode].
     *
     * @return [SettingsResult.Failure] with [SettingsError.WriteFailed] when the store rejected
     *   the write, in which case [themeMode] is unchanged.
     */
    public fun setThemeMode(value: ThemeMode): SettingsResult

    /**
     * Persists [value] as the display language and publishes it to [language]. `null` means
     * "follow the operating system" and is always accepted.
     *
     * @return [SettingsResult.Failure] with [SettingsError.UnsupportedLanguage] when [value] is
     *   not in [SettingsConfig.supportedLanguages] — nothing is written — or with
     *   [SettingsError.WriteFailed] when the store rejected the write. [language] is unchanged in
     *   both cases.
     */
    public fun setLanguage(value: LanguageTag?): SettingsResult
}
