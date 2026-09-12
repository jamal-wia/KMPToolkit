package io.github.jamal_wia.kmptoolkit.language

/**
 * An [AppLanguage] whose reading direction is derived from [code], rather than stated.
 *
 * Which languages an app offers is a product decision this module deliberately knows nothing about —
 * but *which direction Arabic reads in* is not. It is a property of the writing system, identical in
 * every app, and stating it by hand on every entry of a supported-language list is a line that can
 * be got wrong silently: an app that types `isLtr = true` next to `"fa"` lays Persian out
 * left-to-right and nothing fails.
 *
 * ```kotlin
 * enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
 *     English(AppLanguage("en"), "English"),
 *     Arabic(AppLanguage("ar"), "Arabic"),
 *     Persian(AppLanguage("fa"), "Persian"),
 * }
 * ```
 *
 * The two-argument constructor is still there and still wins, for a language this function gets
 * wrong for your purposes or does not know about — see [isRightToLeft] for exactly what it knows.
 *
 * @param code BCP-47 / ISO language tag (`"en"`, `"ar"`, `"pt-BR"`, `"az-Arab"`, …). Never `null`:
 *   "follow the device" is [AppLanguage.System], which has no direction of its own to derive.
 */
public fun AppLanguage(code: String): AppLanguage =
    AppLanguage(code = code, isLtr = !isRightToLeft(code))

/**
 * Whether [code] names a language written right-to-left.
 *
 * An explicit script subtag decides on its own, because it is the writing system that has a
 * direction and not the language: `az-Arab` is right-to-left where `az` is not, and `pa-Arab`
 * (Shahmukhi) where `pa` (Gurmukhi) is not. Only when there is no script subtag does the primary
 * language subtag decide, using its default script.
 *
 * What it knows is the set of right-to-left scripts and languages in actual use for user interfaces,
 * not every script ever encoded: the right-to-left scripts `Arab`, `Hebr`, `Thaa`, `Syrc`, `Nkoo`,
 * `Adlm`, `Mand`, `Samr`, `Rohg`, `Yezi`, `Mend`, and the languages that default to one of them.
 * Anything it does not recognise is reported left-to-right, which is the safe direction to be wrong
 * in: an unexpected language renders in the majority layout rather than mirrored. If you need a
 * language it does not cover, pass `isLtr` explicitly.
 *
 * Legacy ISO codes are handled: `iw` (Hebrew) and `ji` (Yiddish) are recognised alongside the
 * modern `he` and `yi`, because a device can still report either.
 */
public fun isRightToLeft(code: String): Boolean {
    val subtags: List<String> = code.split('-', '_').filter(String::isNotEmpty)
    if (subtags.isEmpty()) return false

    // A script subtag is the four-letter one, and there is at most one. Checked before the language,
    // because a language written in a script it does not default to follows the script.
    val script: String? = subtags.drop(1).firstOrNull { subtag -> subtag.length == SCRIPT_TAG_LENGTH }
    if (script != null) return script.lowercase() in RIGHT_TO_LEFT_SCRIPTS

    return subtags[0].lowercase() in RIGHT_TO_LEFT_LANGUAGES
}

/** ISO 15924 script subtags are always four letters; nothing else in a tag is. */
private const val SCRIPT_TAG_LENGTH: Int = 4

/** Lowercased ISO 15924 codes for the right-to-left scripts a user interface is written in. */
private val RIGHT_TO_LEFT_SCRIPTS: Set<String> = setOf(
    "arab", // Arabic
    "aran", // Nastaliq (Arabic variant)
    "hebr", // Hebrew
    "thaa", // Thaana (Dhivehi)
    "syrc", // Syriac
    "nkoo", // N'Ko
    "adlm", // Adlam (Fulani)
    "mand", // Mandaic
    "samr", // Samaritan
    "rohg", // Hanifi Rohingya
    "yezi", // Yezidi
    "mend", // Mende Kikakui
)

/**
 * Lowercased primary language subtags whose default script is right-to-left.
 *
 * A language appears here only if its *default* script is right-to-left, which is narrower than "is
 * sometimes written right-to-left". Kurdish is here as `ckb` (Central Kurdish, Arabic script) but
 * not as `ku`, whose default is Latin-script Kurmanji; Fulah is absent for the same reason, since
 * only `ff-Adlm` is right-to-left and the script subtag already decides that case.
 */
private val RIGHT_TO_LEFT_LANGUAGES: Set<String> = setOf(
    "ar", // Arabic
    "arc", // Aramaic
    "ckb", // Central Kurdish (Sorani)
    "dv", // Dhivehi
    "fa", // Persian
    "he", "iw", // Hebrew, modern and legacy
    "ks", // Kashmiri
    "nqo", // N'Ko
    "prs", // Dari
    "ps", // Pashto
    "sd", // Sindhi
    "syr", // Syriac
    "ug", // Uyghur
    "ur", // Urdu
    "yi", "ji", // Yiddish, modern and legacy
)
