package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The device-language matching rules of `resolveSystemLanguage`, one assertion per row: lookup by truncation
 * (`zh-Hans-CN` -> `zh-Hans` -> `zh`), the Chinese script inferred from the region, a same-language
 * same-script sibling, extensions ignored, legacy codes remapped, and the documented limits.
 */
class CatalogMatchingRulesTest {

    /** The first code is the fallback (the first entry of [supported]). */
    private fun resolve(device: String, vararg supported: String): String? {
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(
            supported = supported.map(::AppLanguage),
            systemLanguageCode = { device },
        )
        return catalog.resolveSystemLanguage().code
    }

    @Test
    fun `taiwan Chinese is served the traditional entry`() = assertEquals("zh-Hant", resolve("zh-TW", "en", "zh-Hans", "zh-Hant"))

    @Test
    fun `hong kong Chinese is served the traditional entry`() = assertEquals("zh-Hant", resolve("zh-HK", "en", "zh-Hans", "zh-Hant"))

    @Test
    fun `macau Chinese is served the traditional entry`() = assertEquals("zh-Hant", resolve("zh-MO", "en", "zh-Hans", "zh-Hant"))

    @Test
    fun `bare Chinese is served the simplified entry`() = assertEquals("zh-Hans", resolve("zh", "en", "zh-Hans", "zh-Hant"))

    @Test
    fun `singapore Chinese is served the simplified entry`() = assertEquals("zh-Hans", resolve("zh-SG", "en", "zh-Hans", "zh-Hant"))

    @Test
    fun `a simplified script device finds the region entry of the same script`() = assertEquals("zh-CN", resolve("zh-Hans-CN", "en", "zh-CN", "zh-TW"))

    @Test
    fun `a traditional script device finds the region entry of the same script`() = assertEquals("zh-TW", resolve("zh-Hant-HK", "en", "zh-CN", "zh-TW"))

    @Test
    fun `a traditional device is served a bare Chinese entry as before`() = assertEquals("zh", resolve("zh-Hant-TW", "en", "zh"))

    @Test
    fun `taiwan Chinese prefers the traditional entry over the bare one`() = assertEquals("zh-Hant", resolve("zh-TW", "en", "zh", "zh-Hant"))

    @Test
    fun `a more specific entry on the truncation path wins over the bare language`() = assertEquals("zh-Hans", resolve("zh-Hans-CN", "en", "zh", "zh-Hans"))

    @Test
    fun `a script is never crossed`() = assertEquals("en", resolve("zh-Hant-TW", "en", "zh-Hans"))

    @Test
    fun `a same-language entry of another region is a sibling`() = assertEquals("pt-PT", resolve("pt-BR", "en", "pt-PT"))

    @Test
    fun `an unknown script does not match a known one`() = assertEquals("en", resolve("sr-RS", "en", "sr-Latn"))

    @Test
    fun `a regional preference extension is ignored and the region still matches`() = assertEquals("en-US", resolve("en-US-u-mu-celsius", "de", "en-US", "en-GB"))

    @Test
    fun `an extension on a bare language is ignored`() = assertEquals("en", resolve("en-u-mu-celsius", "de", "en"))

    @Test
    fun `a legacy language code with a region is remapped`() = assertEquals("he", resolve("iw-IL", "en", "he"))

    @Test
    fun `an underscore device tag is read as a hyphen`() = assertEquals("ar-EG", resolve("ar_EG", "en", "ar-EG"))

    @Test
    fun `without likely-subtag data the first listed sibling is taken`() = assertEquals("es-ES", resolve("es-MX", "en", "es-ES", "es-419"))

    @Test
    fun `an empty device tag is the fallback`() = assertEquals("en", resolve("", "en", "fr"))
}
