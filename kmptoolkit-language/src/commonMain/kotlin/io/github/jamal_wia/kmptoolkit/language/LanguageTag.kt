package io.github.jamal_wia.kmptoolkit.language

/**
 * The tag to hand to a platform that parses BCP-47 (`Locale.forLanguageTag`, `AppleLanguages`): the
 * [AppLanguage.code] with an underscore read as a hyphen, or `null` for [AppLanguage.System].
 *
 * In an [AppLanguage.code] an underscore means a hyphen (`ar_EG` is `ar-EG`), the same reading
 * [isRightToLeft] has always had, so a code that is laid out right-to-left is also applied as that
 * locale rather than as the root locale. Only the plain `ll_RR` form is read this way; the script form
 * `Locale.toString()` writes (`sr_RS_#Latn`) is not part of the contract.
 */
internal fun AppLanguage.platformTag(): String? = code?.replace('_', '-')

/**
 * The form two language codes are compared in: lower case, `_` read as `-`, cut before the first
 * single-character subtag (a `-u-...` extension or `-x-...` private use is not part of the language), and
 * with the JDK's legacy remap applied to the language subtag (`in` to `id`, `iw` to `he`, `ji` to `yi`).
 */
internal fun canonicalTag(tag: String): String {
    val subtags: MutableList<String> = tag.lowercase()
        .replace('_', '-')
        .split('-')
        .filter(String::isNotEmpty)
        .takeWhile { subtag -> subtag.length > 1 }
        .toMutableList()
    if (subtags.isNotEmpty()) subtags[0] = LEGACY_LANGUAGE_CODES[subtags[0]] ?: subtags[0]
    return subtags.joinToString("-")
}

private val LEGACY_LANGUAGE_CODES: Map<String, String> = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
