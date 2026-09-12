package io.github.jamal_wia.kmptoolkit.language

import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

public actual fun applyLanguageGlobally(language: AppLanguage) {
    val locale: Locale = language.code?.let(Locale::forLanguageTag) ?: systemLocale()
    Locale.setDefault(locale)
    LocaleList.setDefault(LocaleList(locale))
}

public actual fun getSystemLanguageCode(): String? {
    // Resources.getSystem() is the device's own configuration, untouched by this process's
    // Locale.setDefault / LocaleList.setDefault — reading LocaleList.getDefault() instead would
    // report whatever applyLanguageGlobally last set, not the device's actual language.
    val locales: LocaleList = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) null else locales[0].toLanguageTag()
}

private fun systemLocale(): Locale {
    val locales: LocaleList = Resources.getSystem().configuration.locales
    return if (locales.isEmpty) Locale.getDefault() else locales[0]
}
