package io.github.jamal_wia.kmptoolkit.language

/**
 * The set of languages an app offers, and the three questions that follow from having one: which
 * language a device should be served, what to write down when the user picks one, and what a written
 * value means on the next launch.
 *
 * This module cannot answer any of those on its own, because it deliberately has no idea how many
 * languages your app has — see [AppLanguage]. You build a catalog from your own list; the catalog
 * then does the matching, so the fiddly parts (a device reporting `pt-BR` when you offer `pt`, a
 * stored value for a language you have since dropped) have one implementation instead of one per
 * call site.
 *
 * Nothing user-facing lives here. Display names, flags and ordering for a picker stay with you,
 * keyed by whatever you already key them by:
 *
 * ```kotlin
 * enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
 *     English(AppLanguage("en", isLtr = true), "English"),
 *     // A real picker writes each name in its own language and script. That text is yours, not this
 *     // module's — which is the whole reason it is not modelled here.
 *     Arabic(AppLanguage("ar", isLtr = false), "Arabic"),
 * }
 *
 * val catalog: AppLanguageCatalog = createAppLanguageCatalog(
 *     supported = SupportedLanguage.entries.map { it.appLanguage },
 * )
 * ```
 */
public interface AppLanguageCatalog {

    /**
     * The languages this app offers, in the order you gave them. Never contains [AppLanguage.System]
     * — "follow the device" is a mode, not a language, and [resolve] is what turns it into one.
     */
    public val supported: List<AppLanguage>

    /**
     * The language to serve a device whose own language this app does not offer. Always an element
     * of [supported].
     */
    public val fallback: AppLanguage

    /** The persisted identity of [AppLanguage.System]. See [idOf]. */
    public val systemId: String

    /**
     * The supported language whose [AppLanguage.code] is [code], or `null` if there is none.
     *
     * Matching ignores case, reads `_` as `-`, ignores extensions (`-u-...`, `-x-...`) and applies the
     * JDK's legacy language-code remaps to the language subtag, so a platform reporting Indonesian as
     * the obsolete `"in"` still finds your `"id"` entry. A code with no language subtag (private use
     * `x-...`, grandfathered `i-...`) is compared by its whole text, case and `_` ignored, empty
     * segments (`x--klingon`) too. Region and script subtags must otherwise match.
     */
    public fun byCode(code: String): AppLanguage?

    /**
     * The string to persist for [language]: its [AppLanguage.code], or [systemId] when that is
     * `null`.
     *
     * Store this rather than the language object itself, and read it back with [fromId]. Two values
     * are then stable across releases — a language's own tag, and whatever you chose for
     * [systemId] — which is what lets a stored selection survive an app update.
     */
    public fun idOf(language: AppLanguage): String

    /**
     * The language a previously stored [idOf] value denotes.
     *
     * An unknown or `null` id resolves to [AppLanguage.System], never to an error: an app that drops
     * a language it used to offer must not strand the users who had picked it, and a first launch
     * with nothing stored is the same question with the same answer.
     */
    public fun fromId(id: String?): AppLanguage

    /**
     * The supported language that best matches the device's own language right now.
     *
     * The device tag is looked up whole, then truncated one subtag at a time (`zh-Hans-CN`, `zh-Hans`,
     * `zh`), so a device set to `pt-BR` is served your `pt-BR` if you offer it and your `pt` otherwise.
     * For Chinese the script is inferred from the region when the tag has none (`zh-TW` is looked up as
     * `zh-Hant`, `zh-SG` and a bare `zh` as `zh-Hans`). When nothing is found, an entry of the same
     * language and the same script is taken (`pt-BR` is served your `pt-PT`, `zh-Hans-CN` your `zh-CN`;
     * the device's own region first, else the first one listed), and only then [fallback]. A script is
     * never crossed: a Traditional device is never served a Simplified entry. Extensions such as
     * Android 14's `-u-mu-celsius` regional preferences are ignored.
     *
     * No likely-subtag data is bundled except the Chinese script, and no CLDR parent locales: a device
     * `sr-RS` is not matched to a `sr-Latn` entry, and `es-MX` against `es-ES` and `es-419` takes the
     * first listed, not `es-419`. Offer explicit tags, or a bare language as the catch-all.
     *
     * Re-read on every call, never cached: a device language change must take effect without your
     * app restarting.
     */
    public fun resolveSystemLanguage(): AppLanguage

    /**
     * [language] itself, or [resolveSystemLanguage] when its [AppLanguage.code] is `null`.
     *
     * This is what to call before reading [AppLanguage.isLtr] or passing a language to
     * `AppLocale` — [AppLanguage.System]'s direction is a placeholder, not an answer. A language
     * outside [supported] is returned unchanged rather than corrected: you asked for it explicitly.
     */
    public fun resolve(language: AppLanguage): AppLanguage
}

/**
 * Creates an [AppLanguageCatalog] over [supported].
 *
 * @param supported the languages your app offers. Must be non-empty, must not contain
 *   [AppLanguage.System], and must not repeat a code (`ar_EG` and `ar-EG` are the same code). Copied: mutating the list afterwards does not
 *   change the catalog.
 * @param fallback the language to serve a device whose own language you do not offer. Must be one of
 *   [supported]; defaults to the first, so listing your default language first is enough.
 * @param systemId the string [AppLanguageCatalog.idOf] returns for [AppLanguage.System]. It is a
 *   value that ends up in *your* storage, so like every other consumer-facing identifier in this
 *   suite it is yours to choose — change it only to match what an existing app already has on disk,
 *   and never after shipping. Must not collide with a supported language's code.
 * @param systemLanguageCode how to read the device's own language. Overriding it is for a test that
 *   wants a fixed device language; production code should leave the default.
 */
public fun createAppLanguageCatalog(
    supported: List<AppLanguage>,
    fallback: AppLanguage = requireNotNull(supported.firstOrNull()) { "supported must not be empty" },
    systemId: String = "system",
    systemLanguageCode: () -> String? = ::getSystemLanguageCode,
): AppLanguageCatalog =
    // Copied: validation and every lookup then see one list, whatever the caller does to theirs later.
    DefaultAppLanguageCatalog(supported.toList(), fallback, systemId, systemLanguageCode)

private class DefaultAppLanguageCatalog(
    override val supported: List<AppLanguage>,
    override val fallback: AppLanguage,
    override val systemId: String,
    private val systemLanguageCode: () -> String?,
) : AppLanguageCatalog {

    init {
        require(supported.isNotEmpty()) { "supported must not be empty" }
        require(supported.none { it.code == null }) {
            "supported must not contain AppLanguage.System — it is a mode, not a language"
        }
        val codes: List<String> = supported.mapNotNull { it.code }
        require(codes.distinctBy(::canonicalTag).size == codes.size) {
            "supported must not repeat a language code: $codes"
        }
        require(fallback in supported) { "fallback must be one of supported: $fallback" }
        require(codes.none { canonicalTag(it) == canonicalTag(systemId) }) {
            "systemId '$systemId' collides with a supported language code"
        }
    }

    private val byNormalizedCode: Map<String, AppLanguage> =
        supported.associateBy { canonicalTag(it.code.orEmpty()) }

    override fun byCode(code: String): AppLanguage? = byNormalizedCode[canonicalTag(code)]

    override fun idOf(language: AppLanguage): String = language.code ?: systemId

    override fun fromId(id: String?): AppLanguage {
        if (id == null || canonicalTag(id) == canonicalTag(systemId)) return AppLanguage.System
        return byCode(id) ?: AppLanguage.System
    }

    private val parsedSupported: List<Pair<AppLanguage, LanguageTag>> =
        supported.map { language -> language to parseLanguageTag(language.code.orEmpty()) }

    override fun resolveSystemLanguage(): AppLanguage {
        val device: LanguageTag = parseLanguageTag(systemLanguageCode() ?: return fallback)
        if (device.language.isEmpty()) return fallback
        // 1. Lookup: the device tag whole, then truncated one subtag at a time down to the bare language.
        lookupCandidates(device).firstNotNullOfOrNull { candidate -> byNormalizedCode[candidate] }?.let { return it }
        // 2. Same language and same script: the entry of the device's region, else the first one listed.
        val siblings: List<Pair<AppLanguage, LanguageTag>> = parsedSupported.filter { (_, tag) ->
            tag.language == device.language && tag.effectiveScript == device.effectiveScript
        }
        val sibling: AppLanguage? =
            (siblings.firstOrNull { (_, tag) -> device.region != null && tag.region == device.region } ?: siblings.firstOrNull())
                ?.first
        // 3. Nothing offered speaks it.
        return sibling ?: fallback
    }

    /**
     * The canonical tags to try, first hit wins: [device] whole; for Chinese without an explicit script, its
     * truncations with the script its region implies inserted after the language (`zh-tw` is tried as
     * `zh-hant-tw`, `zh-hant`), longest first and without the bare language; then the truncations of [device]
     * itself, longest first, ending with the bare language (`zh-hans-cn`, `zh-hans`, `zh`).
     */
    private fun lookupCandidates(device: LanguageTag): Set<String> {
        val candidates: MutableSet<String> = linkedSetOf(device.canonical)
        val inferredScript: String? = device.effectiveScript
        if (device.language == "zh" && device.script == null && inferredScript != null) {
            val withScript: List<String> = listOf(device.language, inferredScript) + device.subtags.drop(1)
            for (length: Int in withScript.size downTo 2) candidates += withScript.take(length).joinToString("-")
        }
        for (length: Int in device.subtags.size downTo 1) candidates += device.subtags.take(length).joinToString("-")
        return candidates
    }

    override fun resolve(language: AppLanguage): AppLanguage =
        if (language.code == null) resolveSystemLanguage() else language
}
