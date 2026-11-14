package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A SUPPORTED entry whose code has no language subtag (a private-use tag `x-...`, BCP-47 section 2.2.7, or a
 * grandfathered `i-...` tag) read against the documented sentences: "an unknown or `null` id resolves to
 * `AppLanguage.System`", `byCode` returns "or `null`", and "must not repeat a code". Such a code is compared by
 * its whole text (case and `_` ignored), so two different ones are two codes and an unknown one is unknown.
 */
class CatalogPrivateUseCodeTest {

    private val english = AppLanguage("en")
    private val klingon = AppLanguage("x-klingon", isLtr = true)

    private fun catalog(vararg supported: AppLanguage, systemId: String = "system"): AppLanguageCatalog =
        createAppLanguageCatalog(
            supported = supported.toList(),
            systemId = systemId,
            systemLanguageCode = { "en" },
        )

    @Test
    fun `an unknown private use id is the system language not another private use entry`() =
        assertEquals(AppLanguage.System, catalog(english, klingon).fromId("x-elvish"))

    @Test
    fun `an unknown private use code is not found by byCode`() =
        assertNull(catalog(english, klingon).byCode("x-elvish"))

    @Test
    fun `two distinct private use codes are not a repeated code`() {
        val elvish = AppLanguage("x-elvish", isLtr = true)
        assertEquals(listOf(english, klingon, elvish), catalog(english, klingon, elvish).supported)
    }

    @Test
    fun `an empty id is the system language even when a private use entry is supported`() =
        assertEquals(AppLanguage.System, catalog(english, klingon).fromId(""))

    @Test
    fun `a private use code is found by its own text with case and underscore ignored`() {
        val catalog: AppLanguageCatalog = catalog(english, klingon)
        assertEquals(klingon, catalog.byCode("X_Klingon"))
        assertEquals(klingon, catalog.fromId("x-KLINGON"))
    }

    @Test
    fun `a private use code is not matched by a longer private use code that starts with it`() =
        assertNull(catalog(english, klingon).byCode("x-klingon-extra"))

    @Test
    fun `two grandfathered codes are two entries and each is found by its own code`() {
        val klingonGrandfathered = AppLanguage("i-klingon", isLtr = true)
        val enochian = AppLanguage("i-enochian", isLtr = true)
        val catalog: AppLanguageCatalog = catalog(english, klingonGrandfathered, enochian)
        assertEquals(klingonGrandfathered, catalog.byCode("i-klingon"))
        assertEquals(enochian, catalog.byCode("I_Enochian"))
        assertEquals(enochian, catalog.fromId("i-enochian"))
    }

    @Test
    fun `a system id of its own beside a private use entry is accepted and resolves to the system language`() {
        val catalog: AppLanguageCatalog = catalog(english, klingon, systemId = "x-system")
        assertEquals(AppLanguage.System, catalog.fromId("x-system"))
        assertEquals(klingon, catalog.fromId("x-klingon"))
    }

    @Test
    fun `a device tag that is a private use code with that code supported still resolves to the fallback`() {
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(
            supported = listOf(english, klingon),
            systemLanguageCode = { "x-klingon" },
        )
        assertEquals(english, catalog.resolveSystemLanguage())
    }
}
