package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals

/** `supported` is copied at construction: the caller's list and the catalog never drift apart. */
class CatalogCopiesSupportedTest {

    private val en = AppLanguage("en")
    private val he = AppLanguage("iw")
    private val ptBr = AppLanguage("pt-BR")

    @Test
    fun `mutating the list passed in afterwards does not change the catalog`() {
        val list: MutableList<AppLanguage> = mutableListOf(en, he)
        val catalog = createAppLanguageCatalog(supported = list, systemLanguageCode = { null })
        list.clear()
        list += ptBr
        assertEquals(listOf(en, he), catalog.supported, "supported follows the caller's list while byCode still follows the original")
        assertEquals(he, catalog.byCode("iw"))
        assertEquals(null, catalog.byCode("pt-BR"))
    }
}
