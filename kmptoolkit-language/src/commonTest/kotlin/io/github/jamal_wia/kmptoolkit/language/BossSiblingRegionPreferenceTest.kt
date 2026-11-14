package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boss round of unit F: the documented sibling rule of `resolveSystemLanguage`, "an entry of the same
 * language and the same script is taken (... `zh-Hans-CN` your `zh-CN`; the device's own region first,
 * else the first one listed)". Every earlier row reached the region-first branch with only one sibling,
 * or with the device's region on the lookup path, so serving the first listed sibling regardless of
 * region survived the whole suite (boss mutant B-M5). Each row below lists the sibling of another region
 * FIRST, so only the region preference can pick the right answer.
 */
class BossSiblingRegionPreferenceTest {

    private fun resolve(device: String, vararg supported: String): String? =
        createAppLanguageCatalog(
            supported = supported.map(::AppLanguage),
            systemLanguageCode = { device },
        ).resolveSystemLanguage().code

    @Test
    fun `a Hong Kong Traditional device is served the Hong Kong entry over a Taiwan entry listed first`() =
        assertEquals("zh-HK", resolve("zh-Hant-HK", "en", "zh-TW", "zh-HK"))

    @Test
    fun `a mainland Simplified device is served the mainland entry over a Singapore entry listed first`() =
        assertEquals("zh-CN", resolve("zh-Hans-CN", "en", "zh-SG", "zh-CN"))

    @Test
    fun `a British device is served the British variant entry over an American entry listed first`() =
        assertEquals("en-GB-oxendict", resolve("en-GB", "fr", "en-US", "en-GB-oxendict"))

    @Test
    fun `without a sibling of its region the device is served the first sibling listed`() =
        assertEquals("zh-TW", resolve("zh-Hant-MO", "en", "zh-TW", "zh-HK"))
}
