package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

public actual fun applyLanguageGlobally(language: AppLanguage) {
    val locale: Locale = language.resolveLocale()
    Locale.setDefault(locale)
    // Compose Multiplatform resolves a string resource through LocaleList.getDefault(), not
    // Locale.getDefault(), on API 24+. Setting only the JVM default leaves every stringResource on
    // the device's language until something else happens to fix it.
    LocaleList.setDefault(LocaleList(locale))
}

public actual fun getSystemLanguageCode(): String? {
    // Resources.getSystem() is the device's own configuration, untouched by this process's
    // Locale.setDefault / LocaleList.setDefault — reading LocaleList.getDefault() instead would
    // report whatever applyLanguageGlobally last set, not the device's actual language.
    //
    // toLanguageTag(), not `.language`: the latter still reports the pre-1989 ISO codes ("in" for
    // Indonesian, "iw" for Hebrew, "ji" for Yiddish) that java.util.Locale preserves for backwards
    // compatibility, which would then fail to match a modern code in a supported-language list.
    val locales: LocaleList = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) null else locales[0].toLanguageTag()
}

/** The locale to run under for this language — the device's own when it is [AppLanguage.System]. */
internal fun AppLanguage.resolveLocale(): Locale =
    code?.let(Locale::forLanguageTag) ?: systemLocale()

private fun systemLocale(): Locale {
    val locales: LocaleList = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) Locale.getDefault() else locales[0]
}

/**
 * A [Configuration] override carrying **only** [locale].
 *
 * Every other field is left undefined so `Configuration.updateFrom` copies nothing but the locale
 * and its layout direction onto whatever base it is merged with. `fontScale` is pinned to `0`
 * explicitly — `Configuration()` already leaves it there, but `setToDefaults()` would put it at `1`,
 * and a `1` here silently discards the user's font-size setting.
 *
 * A full copy of a base configuration must **not** be used instead: a context built from one keeps
 * every copied field for its whole life — dark mode, orientation, density, font scale — because the
 * override wins over each later system update. That is harmless for an activity, which is recreated
 * on every such change anyway, but it would freeze an application context permanently.
 */
internal fun localeOverrideConfiguration(locale: Locale): Configuration =
    Configuration().apply {
        fontScale = 0f
        setLocale(locale)
    }

/** The [Locale]-level form of [localizedContext], for callers that already resolved one. */
internal fun localizedContext(base: Context, locale: Locale): Context =
    base.createConfigurationContext(localeOverrideConfiguration(locale))
