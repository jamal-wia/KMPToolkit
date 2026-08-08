package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only [AppSettings] there is: three `MutableStateFlow`s over a [KeyValueStorage].
 *
 * Internal because the contract is the interface and the loading rules are
 * [createAppSettings]'s — a consumer who could construct this directly could construct one with
 * initial values that never came from the store, which is exactly the "shows a value it did not
 * load" bug the load problems exist to prevent.
 */
internal class DefaultAppSettings(
    private val storage: KeyValueStorage,
    private val config: SettingsConfig,
    initialFontScale: FontScale,
    initialThemeMode: ThemeMode,
    initialLanguage: LanguageTag?,
) : AppSettings {

    private val _fontScale: MutableStateFlow<FontScale> = MutableStateFlow(initialFontScale)
    override val fontScale: StateFlow<FontScale> = _fontScale.asStateFlow()

    private val _themeMode: MutableStateFlow<ThemeMode> = MutableStateFlow(initialThemeMode)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _language: MutableStateFlow<LanguageTag?> = MutableStateFlow(initialLanguage)
    override val language: StateFlow<LanguageTag?> = _language.asStateFlow()

    override fun setFontScale(value: FontScale): SettingsResult = persist(
        flow = _fontScale,
        key = config.fontScaleKey,
        value = value,
        encoded = value.multiplier.toString(),
    )

    override fun setThemeMode(value: ThemeMode): SettingsResult = persist(
        flow = _themeMode,
        key = config.themeModeKey,
        value = value,
        encoded = value.name,
    )

    override fun setLanguage(value: LanguageTag?): SettingsResult {
        // Checked before anything is written: a language the app has no strings for is a caller
        // mistake to report, not a preference to persist and then have to fall back from at every
        // subsequent launch.
        if (value != null && !config.supports(value)) {
            return SettingsResult.Failure(SettingsError.UnsupportedLanguage(value))
        }
        return persist(
            flow = _language,
            key = config.languageKey,
            value = value,
            encoded = value?.value ?: SYSTEM_LANGUAGE,
        )
    }

    /**
     * Writes [encoded] under [key] and, only if that succeeded, publishes [value] to [flow].
     *
     * Store first, flow second, deliberately: a flow that moved before the write would show the
     * user a choice that silently reverts at the next launch, and undoing it afterwards would make
     * collectors see the value flicker in and back out.
     *
     * The equality short-circuit is not an optimisation for its own sake — without it, re-selecting
     * the value that is already active would write to the store on every tap of a settings row.
     */
    private fun <T> persist(
        flow: MutableStateFlow<T>,
        key: String,
        value: T,
        encoded: String,
    ): SettingsResult {
        if (flow.value == value) return SettingsResult.Success
        return when (val write: StorageResult<Unit> = storage.put(key, encoded)) {
            is StorageResult.Success -> {
                flow.value = value
                SettingsResult.Success
            }

            is StorageResult.Failure -> SettingsResult.Failure(
                SettingsError.WriteFailed(key, write.error),
            )
        }
    }
}
