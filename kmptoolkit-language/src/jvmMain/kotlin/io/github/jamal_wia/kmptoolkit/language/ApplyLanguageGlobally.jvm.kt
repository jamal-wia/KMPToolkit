package io.github.jamal_wia.kmptoolkit.language

import java.util.IllformedLocaleException
import java.util.Locale

public actual fun applyLanguageGlobally(language: AppLanguage) {
    // Locale.setDefault(Locale) sets every category at once — DISPLAY and FORMAT alike — which is
    // what a language selection means: strings, dates and numbers all follow it.
    Locale.setDefault(language.code?.let(Locale::forLanguageTag) ?: systemLocale())
}

public actual fun getSystemLanguageCode(): String? =
    systemLocale().toLanguageTag().takeUnless { tag -> tag == UNDETERMINED_LANGUAGE_TAG }

/**
 * The language the operating system gave this JVM, as it stood at launch.
 *
 * Read from the `user.*` system properties rather than from `Locale.getDefault()`, because the two
 * stop agreeing the moment [applyLanguageGlobally] runs: the JVM derives its initial default from
 * those properties, and `Locale.setDefault` changes the default without writing the properties back.
 * Reading the default would report the last applied selection and make [AppLanguage.System] a no-op
 * — restoring the language you had just replaced with itself.
 *
 * A launcher that passes `-Duser.language=…` is making exactly the declaration this function answers,
 * so that override is honoured rather than worked around.
 */
internal fun systemLocale(): Locale {
    val language: String = System.getProperty("user.language").orEmpty()
    val script: String = System.getProperty("user.script").orEmpty()
    val region: String = System.getProperty("user.country").orEmpty()
    return try {
        Locale.Builder().setLanguage(language).setScript(script).setRegion(region).build()
    } catch (_: IllformedLocaleException) {
        // A malformed script or region in the environment must not cost the language itself.
        Locale.forLanguageTag(language)
    }
}

/** What `Locale.toLanguageTag()` reports for a locale with no language at all. */
private const val UNDETERMINED_LANGUAGE_TAG: String = "und"
