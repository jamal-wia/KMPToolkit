package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Device tags that carry a calendar, numbering or private-use extension (`ar-SA-u-ca-islamic-umalqura`, a Hijri
 * or Buddhist calendar picked in the device settings) resolve exactly as the tag without the extension, a
 * code in the supported list that carries one matches the same way, and the reading direction of such a code is
 * the direction of its language, whatever four-letter subtag the extension holds.
 */
class CatalogCalendarAndPrivateUseTagTest {

    private val supported: List<AppLanguage> =
        listOf("en", "ar-SA", "ar", "th-TH", "fa-IR", "he", "pt-BR", "zh-Hans", "zh-Hant").map { AppLanguage(it) }

    private fun resolve(device: String): String? =
        createAppLanguageCatalog(supported, systemLanguageCode = { device }).resolveSystemLanguage().code

    @Test
    fun `a calendar or numbering extension on the device tag is ignored`() {
        val rows: List<Pair<String, String>> = listOf(
            "ar-SA-u-ca-islamic-umalqura" to "ar-SA",
            "ar-SA-u-ca-islamic-civil-nu-arab" to "ar-SA",
            "ar-EG-u-ca-islamic" to "ar",
            "th-TH-u-ca-buddhist-nu-thai" to "th-TH",
            "fa-IR-u-ca-persian" to "fa-IR",
            "he-IL-u-ca-hebrew" to "he",
            "pt-BR-u-ca-gregory-mu-celsius" to "pt-BR",
            "zh-Hant-TW-u-ca-roc" to "zh-Hant",
            "en-US-u-ca-hebrew-x-private" to "en",
            "en-x-ar-SA" to "en",
        )
        for ((device: String, expected: String) in rows) {
            assertEquals(expected, resolve(device), "device tag $device")
        }
    }

    @Test
    fun `a supported code that carries an extension is found by the tag without it`() {
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(
            listOf(AppLanguage("en"), AppLanguage("ar-SA-u-ca-islamic")),
            systemLanguageCode = { "ar-SA" },
        )

        assertEquals("ar-SA-u-ca-islamic", catalog.resolveSystemLanguage().code)
        assertEquals("ar-SA-u-ca-islamic", catalog.byCode("ar-SA")?.code)
        assertEquals("ar-SA-u-ca-islamic", catalog.fromId("AR_sa-u-ca-hebrew").code)
    }

    @Test
    fun `the direction of a code with an extension is the direction of its language`() {
        assertFalse(AppLanguage("ar-SA-u-ca-islamic-umalqura").isLtr)
        assertFalse(AppLanguage("ar-u-nu-latn").isLtr)
        assertFalse(AppLanguage("fa-IR-u-ca-persian").isLtr)
        assertTrue(AppLanguage("en-u-ca-hebr").isLtr)
        assertTrue(AppLanguage("en-x-arab").isLtr)
        assertTrue(AppLanguage("th-TH-u-ca-buddhist").isLtr)
        assertFalse(AppLanguage("he-u-ca-hebrew-x-latn").isLtr)
    }

    @Test
    fun `a private use or undetermined device tag is the fallback and never throws`() {
        for (device in listOf("x-ar-SA", "qaa-x-ar", "und-u-ca-islamic", "und", "i-klingon", "zz-ZZ")) {
            assertEquals("en", resolve(device), "device tag $device")
        }
    }
}
