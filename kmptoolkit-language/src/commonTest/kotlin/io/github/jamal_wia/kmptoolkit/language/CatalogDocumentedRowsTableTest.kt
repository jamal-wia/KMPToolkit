package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A table of device tag / supported list / expected answer rows, each one read straight off a sentence of the
 * `resolveSystemLanguage` KDoc and `docs/kmptoolkit-language/03-guide.md`. The first supported entry is the fallback.
 */
class CatalogDocumentedRowsTableTest {

    private class Row(val device: String?, val supported: List<String>, val expected: String, val rule: String)

    private fun row(device: String?, supported: String, expected: String, rule: String): Row =
        Row(device, supported.split(' ').filter(String::isNotEmpty), expected, rule)

    private val rows: List<Row> = listOf(
        row("pt-BR", "en pt", "pt", "truncation: the bare language when the region is not offered"),
        row("pt-BR", "en pt pt-BR", "pt-BR", "the whole tag first"),
        row("PT_br", "en pt-BR", "pt-BR", "case ignored, underscore read as hyphen"),
        row("pt-BR", "en pt-PT", "pt-PT", "same language and script sibling"),
        row("pt-BR", "en pt-PT pt-AO", "pt-PT", "sibling: first listed when the region is not there"),
        row("pt-AO", "en pt-PT pt-AO", "pt-AO", "sibling: the device's own region first"),
        row("zh-Hans-CN", "en zh-Hans", "zh-Hans", "truncation zh-Hans-CN to zh-Hans"),
        row("zh-Hant-TW", "en zh-Hant", "zh-Hant", "truncation zh-Hant-TW to zh-Hant"),
        row("zh-TW", "en zh-Hant", "zh-Hant", "script inferred from the region (Taiwan)"),
        row("zh-HK", "en zh-Hant zh-Hans", "zh-Hant", "script inferred from the region (Hong Kong)"),
        row("zh-MO", "en zh-Hant zh-Hans", "zh-Hant", "script inferred from the region (Macau)"),
        row("zh-SG", "en zh-Hant zh-Hans", "zh-Hans", "zh-SG as zh-Hans"),
        row("zh-CN", "en zh-Hant zh-Hans", "zh-Hans", "any other region is Simplified"),
        row("zh-MY", "en zh-Hant zh-Hans", "zh-Hans", "any other region is Simplified (Malaysia)"),
        row("zh", "en zh-Hant zh-Hans", "zh-Hans", "bare zh as zh-Hans"),
        row("zh-Hant", "en zh-Hans-CN", "en", "a script is never crossed (Traditional device, Simplified entry)"),
        row("zh-Hans", "en zh-Hant-TW", "en", "a script is never crossed (Simplified device, Traditional entry)"),
        row("zh-CN", "en zh-TW zh-HK", "en", "a script is never crossed (inferred Simplified, Traditional entries)"),
        row("zh-TW", "en zh-CN", "en", "a script is never crossed (inferred Traditional, Simplified entry)"),
        row("zh-Hans-TW", "en zh-TW", "en", "an explicit script beats the one the region implies"),
        row("zh-Hant-CN", "en zh-CN", "en", "an explicit script beats the one the region implies (reverse)"),
        row("zh-Hans-CN", "en zh-CN zh-TW", "zh-CN", "same script sibling of the device's own region"),
        row("zh-Hant-HK", "en zh-CN zh-TW", "zh-TW", "same script sibling, other region"),
        row("zh-TW", "en zh-HK zh-CN", "zh-HK", "inferred Traditional finds a Traditional sibling"),
        row("sr-Latn-RS", "en sr-Latn", "sr-Latn", "truncation keeps the script"),
        row("sr-Latn-RS", "en sr", "sr", "truncation down to the bare language"),
        row("sr-Cyrl-RS", "en sr-Latn", "en", "a script is never crossed (Serbian)"),
        row("sr-RS", "en sr-Latn", "en", "no likely-subtag data beyond Chinese"),
        row("sr-Latn-RS", "en sr-RS", "en", "no likely-subtag data beyond Chinese (reverse)"),
        row("es-MX", "en es-ES es-419", "es-ES", "no CLDR parent locales: first listed sibling"),
        row("es-419", "en es", "es", "numeric region truncates"),
        row("es-419", "en es-ES", "es-ES", "numeric region, sibling"),
        row("en-AU", "de en-US en-GB", "en-US", "first listed sibling"),
        row("en-GB", "de en-US en-GB", "en-GB", "own region"),
        row("iw-IL", "en he", "he", "legacy code remapped (device)"),
        row("he-IL", "en iw", "iw", "legacy code remapped (supported)"),
        row("in", "en id", "id", "legacy code remapped (in)"),
        row("id-ID", "en in", "in", "legacy code remapped (supported in)"),
        row("ji", "en yi", "yi", "legacy code remapped (ji)"),
        row("en-US-u-mu-celsius", "de en-US en-GB", "en-US", "extension ignored"),
        row("en-US-u-ca-buddhist-nu-thai", "de en-US en-GB", "en-US", "longer -u- extension ignored"),
        row("en-US-x-lvariant-POSIX", "de en-US", "en-US", "private use ignored (the JDK POSIX locale)"),
        row("en-u-mu-celsius", "de en", "en", "extension on a bare language"),
        row("ar_EG", "en ar-EG", "ar-EG", "underscore device tag"),
        row("ar-EG", "en ar_EG", "ar_EG", "underscore supported code"),
        row("ca-ES-valencia", "en ca-ES", "ca-ES", "variant truncated"),
        row("de-CH-1901", "en de-CH de", "de-CH", "variant truncated to the region entry"),
        row("fil-PH", "en fil", "fil", "three letter language"),
        row("und", "en fr", "en", "undetermined language is the fallback"),
        row("", "en fr", "en", "empty device tag is the fallback"),
        row(null, "en fr", "en", "no device language is the fallback"),
        row("ja-JP", "en fr", "en", "nothing offered speaks it"),
        row("fr-CA", "en fr", "fr", "regional device, bare entry"),
        row("fr", "en fr-CA", "fr-CA", "bare device, regional entry (same language, same script)"),
        row("zh", "en zh-TW zh-CN", "zh-CN", "bare zh finds a Simplified region entry, skipping a Traditional one listed first"),
        row("zh-Hant", "en zh-CN zh-TW", "zh-TW", "Traditional script device skips a Simplified entry listed first"),
    )

    @Test
    fun `every documented row holds`() {
        val failures: List<String> = rows.mapNotNull { r ->
            val catalog: AppLanguageCatalog = createAppLanguageCatalog(
                supported = r.supported.map { AppLanguage(it, isLtr = true) },
                systemLanguageCode = { r.device },
            )
            val actual: String? = catalog.resolveSystemLanguage().code
            if (actual == r.expected) null else "device '${r.device}' over ${r.supported}: expected ${r.expected} but was $actual  [${r.rule}]"
        }
        assertEquals(emptyList(), failures)
    }
}
