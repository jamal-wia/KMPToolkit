package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Edge inputs for the catalog's documented lookups and its validation. */
class CatalogContractEdgeTest {

    private val en = AppLanguage("en")
    private val he = AppLanguage("iw") // legacy code on purpose
    private val id = AppLanguage("in") // legacy code on purpose
    private val ptBr = AppLanguage("pt-BR")

    private fun catalog(system: String? = null, vararg languages: AppLanguage): AppLanguageCatalog =
        createAppLanguageCatalog(supported = languages.toList(), systemLanguageCode = { system })

    @Test
    fun `every supported language survives idOf and fromId whatever its case or legacy code`() {
        val catalog = catalog(null, en, he, id, ptBr, AppLanguage("zh-Hant"))
        for (language in catalog.supported) assertSame(language, catalog.fromId(catalog.idOf(language)), "round trip of ${language.code}")
        assertEquals(AppLanguage.System, catalog.fromId(catalog.idOf(AppLanguage.System)))
        assertEquals(AppLanguage.System, catalog.fromId("  "), "blank is an unknown id, not an error")
    }

    @Test
    fun `a device tag is matched whole then on its primary subtag whatever its case or legacy code`() {
        assertSame(he, catalog("HE-il", en, he).resolveSystemLanguage())
        assertSame(id, catalog("id-ID", en, id).resolveSystemLanguage())
        assertSame(ptBr, catalog("pt-br", en, ptBr, AppLanguage("pt")).resolveSystemLanguage())
        assertSame(en, catalog("ja-JP", en, he).resolveSystemLanguage(), "no match falls back")
    }
}
