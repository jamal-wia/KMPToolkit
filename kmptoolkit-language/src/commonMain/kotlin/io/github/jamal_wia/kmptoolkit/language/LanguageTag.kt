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
 * A language tag in the form two codes are compared in, with the parts matching reads.
 *
 * [canonical] is lower case, reads `_` as `-`, is cut before the first single-character subtag (a `-u-...`
 * extension or `-x-...` private use is not part of the language), and has the JDK's legacy remap applied to
 * the language subtag (`in` to `id`, `iw` to `he`, `ji` to `yi`). [script] is an explicit four-letter script
 * subtag, [region] a two-letter or three-digit one.
 */
internal class LanguageTag(val subtags: List<String>) {

    val canonical: String = subtags.joinToString("-")
    val language: String = subtags.firstOrNull().orEmpty()
    val script: String?
    val region: String?

    init {
        val position: Int = positionAfterExtlangs(subtags)
        script = subtags.getOrNull(position)?.takeIf(String::isScriptSubtag)
        val regionIndex: Int = if (script != null) position + 1 else position
        region = subtags.getOrNull(regionIndex)?.takeIf { subtag -> subtag.isRegionSubtag() }
    }

    /**
     * The explicit [script], else for Chinese the one its region implies (Traditional in Taiwan, Hong Kong
     * and Macau, Simplified anywhere else, a bare `zh` included), else `null`: no likely-subtag data is
     * bundled for any other language.
     */
    val effectiveScript: String? = script ?: if (language == "zh") {
        if (region in TRADITIONAL_CHINESE_REGIONS) "hant" else "hans"
    } else {
        null
    }
}

internal fun parseLanguageTag(tag: String): LanguageTag {
    val subtags: MutableList<String> = tag.lowercase()
        .replace('_', '-')
        .split('-')
        .filter(String::isNotEmpty)
        .takeWhile { subtag -> subtag.length > 1 }
        .toMutableList()
    if (subtags.isNotEmpty()) subtags[0] = LEGACY_LANGUAGE_CODES[subtags[0]] ?: subtags[0]
    return LanguageTag(subtags)
}

internal fun canonicalTag(tag: String): String = parseLanguageTag(tag).canonical

private fun String.isRegionSubtag(): Boolean =
    (length == 2 && all(Char::isLetter)) || (length == 3 && all(Char::isDigit))

private val TRADITIONAL_CHINESE_REGIONS: Set<String> = setOf("tw", "hk", "mo")

private val LEGACY_LANGUAGE_CODES: Map<String, String> = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
