package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `createAppLanguageCatalog` documents `supported` as "must be non-empty" and rejects an empty list with
 * `IllegalArgumentException`; the default value of `fallback` must not turn the same mistake into a
 * different exception type (`supported.first()` throws `NoSuchElementException` on an empty list).
 */
class CatalogEmptySupportedDefaultFallbackTest {

    @Test
    fun `an empty supported list is rejected the same way when the fallback is left at its default`() {
        val failure: IllegalArgumentException = assertFailsWith<IllegalArgumentException> {
            createAppLanguageCatalog(supported = emptyList())
        }
        assertEquals("supported must not be empty", failure.message)
    }
}
