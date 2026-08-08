package io.github.jamal_wia.kmptoolkit.settings

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult

/**
 * A ready [AppSettings] together with everything that went wrong while reading it.
 *
 * The pair exists because there is no honest single return value here. Loading settings must
 * always produce a working [AppSettings] — an app cannot refuse to start because a preference file
 * is unreadable — but falling back to defaults and saying nothing is exactly how "the app keeps
 * forgetting my theme" ships. So the fallback happens *and* is reported, and the consumer decides
 * what a report is worth: log it, and where a value was merely [SettingsError.UnreadableValue],
 * consider writing the default back to clean the entry up.
 *
 * [problems] is empty in the overwhelmingly common case — a fresh install and a store holding
 * three values it wrote itself both load cleanly.
 *
 * ```kotlin
 * val (settings, problems) = createAppSettings(storage)
 * problems.forEach { logger.w("settings") { "could not load: $it" } }
 * ```
 *
 * @param settings the settings, populated with whatever loaded and defaults for whatever did not.
 * @param problems one entry per setting that did not load, in the order the settings were read
 *   (font scale, theme mode, language). Never more than one per setting.
 */
public data class SettingsLoad(
    public val settings: AppSettings,
    public val problems: List<SettingsError>,
)

/**
 * Reads the three settings out of [storage] and returns an [AppSettings] over them, plus whatever
 * could not be read.
 *
 * Reading happens once, here, so that every later read is a flow lookup rather than a store hit —
 * a font scale is read on every recomposition, and a store round trip per read would be felt. The
 * cost of that choice is stated on [AppSettings]: this instance does not see writes made through
 * another one.
 *
 * Construct exactly one per app, at start-up, and hold it for the app's lifetime. It owns no
 * native handle, registers no listener, and needs no release.
 *
 * @param storage where the three values live. Any [KeyValueStorage] will do — these are
 *   preferences, not secrets, so the plain store is the right one. It is shared with whatever else
 *   the app keeps there; [SettingsConfig.keyPrefix] is what keeps the keys from colliding.
 * @param config the keys, the accepted languages, and the defaults — see [SettingsConfig].
 */
public fun createAppSettings(
    storage: KeyValueStorage,
    config: SettingsConfig = SettingsConfig(),
): SettingsLoad {
    val problems: MutableList<SettingsError> = mutableListOf()

    val fontScale: FontScale = storage.load(
        key = config.fontScaleKey,
        default = config.defaultFontScale,
        problems = problems,
        parse = { raw -> raw.toFloatOrNull()?.let(FontScale::of) },
    )
    val themeMode: ThemeMode = storage.load(
        key = config.themeModeKey,
        default = config.defaultThemeMode,
        problems = problems,
        parse = { raw -> ThemeMode.entries.firstOrNull { it.name == raw } },
    )
    val language: LanguageTag? = storage.loadLanguage(config, problems)

    return SettingsLoad(
        settings = DefaultAppSettings(storage, config, fontScale, themeMode, language),
        problems = problems.toList(),
    )
}

/**
 * Reads one setting, recording why it fell back to [default] when it did.
 *
 * [parse] returns `null` for a value this setting cannot take, which covers both a syntactically
 * wrong entry (`"large"` where a number belongs) and a syntactically fine but unacceptable one (a
 * font scale of `9.0`) — from a caller's perspective those are the same event, and both leave the
 * entry alone for the next write to overwrite.
 */
private fun <T> KeyValueStorage.load(
    key: String,
    default: T,
    problems: MutableList<SettingsError>,
    parse: (String) -> T?,
): T {
    val raw: String? = when (val read = get(key)) {
        is StorageResult.Success -> read.value
        is StorageResult.Failure -> {
            problems += SettingsError.ReadFailed(key, read.error)
            return default
        }
    }
    if (raw == null) return default
    val parsed: T? = parse(raw)
    if (parsed == null) problems += SettingsError.UnreadableValue(key, raw)
    return parsed ?: default
}

/**
 * The language needs its own read because it has one value the others do not: the empty string,
 * which is how "follow the operating system" is persisted.
 *
 * It has to be persisted as *something* — removing the key instead would make "system" and "never
 * chose anything" the same entry, and a user who deliberately picks "system" in an app whose
 * [SettingsConfig.defaultLanguage] is not `null` would find their own default back at the next
 * launch. The empty string is unambiguous because it is not a well-formed language tag, so it can
 * never collide with a real one.
 */
private fun KeyValueStorage.loadLanguage(
    config: SettingsConfig,
    problems: MutableList<SettingsError>,
): LanguageTag? {
    val key: String = config.languageKey
    val raw: String? = when (val read = get(key)) {
        is StorageResult.Success -> read.value
        is StorageResult.Failure -> {
            problems += SettingsError.ReadFailed(key, read.error)
            return config.defaultLanguage
        }
    }
    return when {
        raw == null -> config.defaultLanguage
        raw == SYSTEM_LANGUAGE -> null
        else -> {
            val tag: LanguageTag? = LanguageTag.ofOrNull(raw)
            when {
                tag == null -> {
                    problems += SettingsError.UnreadableValue(key, raw)
                    config.defaultLanguage
                }

                // A tag the app no longer supports: valid when it was written, dropped by an
                // update since. Reported apart from a malformed one because the two call for
                // different reactions — this one is a translation that went away.
                !config.supports(tag) -> {
                    problems += SettingsError.UnsupportedLanguage(tag)
                    config.defaultLanguage
                }

                else -> tag
            }
        }
    }
}

/** How "follow the operating system" is written to the store — see [loadLanguage]. */
internal const val SYSTEM_LANGUAGE: String = ""
