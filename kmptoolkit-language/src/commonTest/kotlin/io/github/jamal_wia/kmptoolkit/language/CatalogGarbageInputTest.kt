package io.github.jamal_wia.kmptoolkit.language

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A device (or a corrupted preference file) can hand the catalog any string. Whatever it is, no lookup throws,
 * `resolveSystemLanguage` answers an entry of `supported`, `byCode` answers an entry or `null`, and `fromId` an
 * entry or `System`; and a tag the matcher cannot read is the fallback rather than an arbitrary entry.
 */
class CatalogGarbageInputTest {

    private val alphabet: List<Char> = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.#@ \t\n\u0000éİı中م😀").toList()

    private fun garbage(r: Random): String = buildString { repeat(r.nextInt(0, 40)) { append(alphabet.random(r)) } }

    @Test
    fun `no input makes a lookup throw or answer outside the catalog`() {
        val supported: List<AppLanguage> = listOf("en", "pt-BR", "zh-Hans", "zh-Hant", "sr-Latn", "ar-EG", "he").map { AppLanguage(it) }
        val r = Random(21)
        repeat(30_000) {
            val device: String = garbage(r)
            val catalog: AppLanguageCatalog = createAppLanguageCatalog(supported, systemLanguageCode = { device })
            assertTrue(catalog.resolveSystemLanguage() in supported, "resolve '$device'")
            val byCode: AppLanguage? = catalog.byCode(device)
            assertTrue(byCode == null || byCode in supported, "byCode '$device'")
            val fromId: AppLanguage = catalog.fromId(device)
            assertTrue(fromId == AppLanguage.System || fromId in supported, "fromId '$device'")
            isRightToLeft(device)
        }
    }

    @Test
    fun `a tag with no readable language is the fallback`() {
        val supported: List<AppLanguage> = listOf("en", "fr").map { AppLanguage(it) }
        for (device in listOf("", "-", "_", "--", "x", "x-private", "u-ca-gregory", "1", "0-0", "  ", "#", ".")) {
            val catalog: AppLanguageCatalog = createAppLanguageCatalog(supported, systemLanguageCode = { device })
            assertEquals("en", catalog.resolveSystemLanguage().code, "device '$device'")
        }
    }
}
