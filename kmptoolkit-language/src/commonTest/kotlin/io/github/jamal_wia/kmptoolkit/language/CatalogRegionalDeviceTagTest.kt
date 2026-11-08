package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `resolveSystemLanguage` serves "the supported language that best matches the device's own language".
 * Platforms report a full tag - Android `Locale.toLanguageTag()` gives `zh-Hans-CN`, iOS
 * `preferredLanguages` gives `zh-Hans-CN` and `sr-Latn-RS` - and an app that offers the script-qualified
 * languages (the usual Chinese and Serbian pair) must be matched on the script, not dropped to the fallback.
 */
class CatalogRegionalDeviceTagTest {

    private val en = AppLanguage("en")
    private val zhHans = AppLanguage("zh-Hans")
    private val zhHant = AppLanguage("zh-Hant")
    private val srLatn = AppLanguage("sr-Latn")

    private fun catalog(device: String): AppLanguageCatalog =
        createAppLanguageCatalog(supported = listOf(en, zhHans, zhHant, srLatn), systemLanguageCode = { device })

    @Test
    fun `a device set to simplified Chinese in China is served the simplified Chinese entry`() {
        assertEquals(zhHans, catalog("zh-Hans-CN").resolveSystemLanguage())
    }

    @Test
    fun `a device set to traditional Chinese in Taiwan is served the traditional Chinese entry`() {
        assertEquals(zhHant, catalog("zh-Hant-TW").resolveSystemLanguage())
    }

    @Test
    fun `a device set to Latin script Serbian is served the Latin Serbian entry`() {
        assertEquals(srLatn, catalog("sr-Latn-RS").resolveSystemLanguage())
    }
}
