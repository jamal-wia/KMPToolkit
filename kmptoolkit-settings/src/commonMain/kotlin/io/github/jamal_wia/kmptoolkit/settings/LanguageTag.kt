package io.github.jamal_wia.kmptoolkit.settings

import kotlin.jvm.JvmInline

/**
 * An IETF BCP 47 language tag — `"en"`, `"pt-BR"`, `"zh-Hant-TW"` — identifying one of the
 * languages an app is translated into.
 *
 * **Not an enum of languages.** A library cannot enumerate its consumers' languages: the set is a
 * property of the app's translation catalogue, it grows between releases, and a closed enum here
 * would mean a new locale needs a new version of *this* library. The set an app supports is
 * declared once in [SettingsConfig.supportedLanguages] and enforced by [AppSettings] against both
 * what is stored and what is set.
 *
 * A tag is also what every platform's own locale API already speaks — `LocaleList.forLanguageTags`
 * on Android, `AppleLanguages` on iOS, `Locale.forLanguageTag` on the JVM — so nothing has to be
 * translated at the boundary. There is no `SYSTEM` member for the same reason: "follow the
 * operating system" is the absence of a choice, and is modelled as a `null` [LanguageTag]
 * throughout this module.
 *
 * ## Canonical form
 *
 * BCP 47 is case-insensitive, but a value class compares by its string, so `"PT-br"` and `"pt-BR"`
 * would otherwise be two different keys into the same set. Every instance is therefore normalised
 * at construction to the conventional casing from the specification: language lowercase, a
 * four-letter script subtag Titlecase, a two-letter region subtag UPPERCASE, and everything else —
 * a three-digit region, a variant, and every subtag inside an extension or private-use sequence
 * (`-u-`, `-t-`, `-x-`) — lowercase. `LanguageTag("PT-br") == LanguageTag("pt-BR")` holds, and
 * [value] is what gets persisted and handed to the platform.
 *
 * ## What is validated, and what is not
 *
 * Only *syntax*: a primary subtag of two to eight letters, then any number of subtags of one to
 * eight letters or digits, separated by `-`. `"en-US"` is accepted, `"english US"` and `"en-"` are
 * not. Whether a syntactically valid tag names a real language, and whether your app has strings
 * for it, are different questions — the second is what [SettingsConfig.supportedLanguages]
 * answers.
 *
 * @param tag the tag text. Throws [IllegalArgumentException] when it is not syntactically a
 *   language tag — a literal in source is a bug to fix at the call site. Use [ofOrNull] for a tag
 *   that arrives from data.
 */
@JvmInline
public value class LanguageTag private constructor(public val value: String) {

    /**
     * The primary language subtag, lowercase: `"pt"` for `"pt-BR"`, `"zh"` for `"zh-Hant-TW"`.
     *
     * For the common "do I have anything at all in this user's language" check, where a Brazilian
     * Portuguese preference should still find a `pt` translation.
     */
    public val language: String get() = value.substringBefore('-')

    override fun toString(): String = value

    public companion object {

        /**
         * [tag] as a canonical [LanguageTag].
         *
         * @throws IllegalArgumentException when [tag] is not syntactically a BCP 47 language tag.
         */
        public operator fun invoke(tag: String): LanguageTag = requireNotNull(ofOrNull(tag)) {
            "tag must be a BCP 47 language tag such as 'en' or 'pt-BR', was '$tag'"
        }

        /**
         * [tag] as a canonical [LanguageTag], or `null` when it is not syntactically a language
         * tag.
         *
         * The non-throwing counterpart of [invoke], for a tag that comes from data rather than
         * from source: a stored preference, a server response, a deep link.
         */
        public fun ofOrNull(tag: String): LanguageTag? {
            val subtags: List<String> = tag.split('-')
            val primary: String = subtags.first()
            if (primary.length !in PRIMARY_LENGTHS || !primary.all { it.isAsciiLetter() }) return null
            val rest: List<String> = subtags.drop(1)
            if (rest.any { it.isEmpty() || it.length > MAX_SUBTAG_LENGTH || !it.isAlphanumeric() }) {
                return null
            }
            return LanguageTag((listOf(primary.lowercase()) + canonicalise(rest)).joinToString("-"))
        }

        /**
         * The conventional casing from BCP 47 § 2.1.1: a four-letter subtag is a script and is
         * written Titlecase, a two-letter subtag is a region and is written uppercase, everything
         * else — a three-digit region, a variant — is lowercase.
         *
         * The walk stops applying those rules at the first **singleton** (a one-character subtag),
         * because a singleton opens an extension or private-use sequence — `-u-`, `-t-`, `-x-` —
         * whose own subtags are lowercase whatever their length. Without that boundary, the
         * calendar key in `en-u-ca-gregory` reads as a two-letter *region* and comes back out as
         * `en-u-CA-gregory`, which is a different string than the one the caller wrote.
         */
        private fun canonicalise(subtags: List<String>): List<String> {
            var inExtension = false
            return subtags.map { subtag ->
                if (subtag.length == SINGLETON_LENGTH) inExtension = true
                when {
                    inExtension -> subtag.lowercase()
                    subtag.length == SCRIPT_LENGTH && subtag.all { it.isAsciiLetter() } ->
                        subtag[0].uppercase() + subtag.substring(1).lowercase()

                    subtag.length == REGION_LENGTH && subtag.all { it.isAsciiLetter() } ->
                        subtag.uppercase()

                    else -> subtag.lowercase()
                }
            }
        }

        // Written out rather than taken from Char.isLetter()/isLetterOrDigit(): those accept the
        // whole Unicode letter category, so 'ру' would pass as a language subtag on its way to a
        // platform API that only speaks ASCII.
        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

        private fun String.isAlphanumeric(): Boolean = all { it.isAsciiLetter() || it in '0'..'9' }

        private val PRIMARY_LENGTHS: IntRange = 2..8
        private const val MAX_SUBTAG_LENGTH: Int = 8
        private const val SCRIPT_LENGTH: Int = 4
        private const val REGION_LENGTH: Int = 2
        private const val SINGLETON_LENGTH: Int = 1
    }
}
