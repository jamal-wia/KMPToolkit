package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** In an `AppLanguage.code`, an underscore means a hyphen: for matching, as it already is for direction. */
class CatalogUnderscoreCodeTest {

    private val en = AppLanguage("en")

    @Test
    fun `a device tag with a hyphen finds the entry written with an underscore`() {
        val arEg = AppLanguage("ar_EG")
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(listOf(en, arEg), systemLanguageCode = { "ar-EG" })

        assertSame(arEg, catalog.resolveSystemLanguage())
        assertSame(arEg, catalog.byCode("ar-EG"))
        assertEquals("ar_EG", catalog.idOf(arEg), "the code is persisted as given")
    }

    @Test
    fun `an entry written with a hyphen is found by an underscore code`() {
        val arEg = AppLanguage("ar-EG")
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(listOf(en, arEg))

        assertSame(arEg, catalog.byCode("ar_EG"))
    }

    @Test
    fun `codes that differ only by underscore versus hyphen are a repeated code`() {
        assertFailsWith<IllegalArgumentException> {
            createAppLanguageCatalog(listOf(AppLanguage("ar_EG"), AppLanguage("ar-EG")))
        }
    }
}
