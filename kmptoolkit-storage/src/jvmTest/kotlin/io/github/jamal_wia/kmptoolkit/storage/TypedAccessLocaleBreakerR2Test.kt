package io.github.jamal_wia.kmptoolkit.storage

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 2 of group sto-api (STO-3, ticket release-5-1-0): typed access under default locales whose
 * digits, minus sign or case rules differ from ASCII (Arabic and Devanagari digits, Persian, Turkish dotted
 * and dotless i). Contract (`TypedAccess.kt`): one encoding per type, the decimal string with an optional
 * ASCII minus and ASCII digits, and exactly `"true"` / `"false"`; a value written under one locale reads back
 * under any other, and the InvalidValue error text is the same constant whatever the locale.
 */
class TypedAccessLocaleBreakerR2Test {

    private val locales: List<Locale> = listOf(
        Locale.forLanguageTag("ar-SA-u-nu-arab"),
        Locale.forLanguageTag("fa-IR-u-nu-arabext"),
        Locale.forLanguageTag("hi-IN-u-nu-deva"),
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("th-TH-u-nu-thai"),
    )

    private suspend fun underEachLocale(block: suspend (Locale) -> Unit) {
        val original: Locale = Locale.getDefault()
        try {
            for (locale: Locale in locales) {
                Locale.setDefault(locale)
                block(locale)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `values written under any locale are ASCII and read back under every other`() = runTest {
        val store = FakeKeyValueStorage()
        underEachLocale { locale: Locale ->
            assertEquals(StorageResult.Success(Unit), store.putInt("i", -42))
            assertEquals(StorageResult.Success(Unit), store.putLong("l", Long.MIN_VALUE))
            assertEquals(StorageResult.Success(Unit), store.putBoolean("b", true))

            assertEquals("-42", store.entries["i"], "putInt under $locale")
            assertEquals("-9223372036854775808", store.entries["l"], "putLong under $locale")
            assertEquals("true", store.entries["b"], "putBoolean under $locale")
            assertEquals(StorageResult.Success(-42), store.getInt("i"), "getInt under $locale")
            assertEquals(StorageResult.Success(Long.MIN_VALUE), store.getLong("l"), "getLong under $locale")
            assertEquals(StorageResult.Success(true), store.getBoolean("b"), "getBoolean under $locale")
        }
    }

    @Test
    fun `locale digits and case folding are never accepted and the error text is the same constant`() = runTest {
        val store = FakeKeyValueStorage()
        val texts: MutableSet<String> = mutableSetOf()
        underEachLocale { locale: Locale ->
            for (stored: String in listOf(String.format(locale, "%d", 1234567), "TRUE", "TRUE".lowercase(locale), "−" + "5")) {
                if (stored == "1234567" || stored == "true") continue
                store.entries["k"] = stored
                for (result: StorageResult<*> in listOf(store.getInt("k"), store.getLong("k"), store.getBoolean("k"))) {
                    val error: StorageError.InvalidValue = assertIs(result.errorOrNull(), "'$stored' under $locale answered $result")
                    val cause: String = error.cause?.message.orEmpty()
                    assertFalse(cause.contains(stored), "the error under $locale quotes '$stored'")
                    texts += cause
                }
            }
        }
        // One text per getter: the error does not vary with the locale or with the rejected value.
        assertEquals(3, texts.size, "the error text varies with the locale or the value: $texts")
    }
}
