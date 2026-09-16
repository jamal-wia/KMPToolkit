package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The contract in `docs/kmptoolkit-language/04-api-reference.md`: direction comes from the writing
 * system, an explicit script subtag outranks the language, and anything unrecognised is reported
 * left-to-right rather than guessed at.
 */
class LanguageDirectionTest {

    @Test
    fun `the common right-to-left languages are recognised`() {
        for (code in listOf("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv", "ckb", "prs")) {
            assertTrue(isRightToLeft(code), "$code reads right-to-left")
        }
    }

    @Test
    fun `left-to-right languages are not`() {
        for (code in listOf("en", "ru", "id", "tr", "pt", "zh", "ja", "ko", "hi", "th", "ku")) {
            assertFalse(isRightToLeft(code), "$code reads left-to-right")
        }
    }

    @Test
    fun `legacy ISO codes are recognised alongside their modern replacements`() {
        // A device can still report either; `iw` and `ji` predate `he` and `yi`.
        assertTrue(isRightToLeft("iw"))
        assertTrue(isRightToLeft("ji"))
    }

    @Test
    fun `a region subtag does not change the direction`() {
        assertTrue(isRightToLeft("ar-EG"))
        assertFalse(isRightToLeft("pt-BR"))
        // Four characters, but a region subtag is never four letters — "419" is Latin America.
        assertFalse(isRightToLeft("es-419"))
    }

    @Test
    fun `an explicit script subtag outranks the language`() {
        // The writing system has the direction, not the language.
        assertTrue(isRightToLeft("az-Arab"), "Azerbaijani in Arabic script")
        assertFalse(isRightToLeft("az"), "Azerbaijani defaults to Latin")
        assertTrue(isRightToLeft("pa-Arab"), "Punjabi in Shahmukhi")
        assertFalse(isRightToLeft("pa"), "Punjabi defaults to Gurmukhi")
        assertTrue(isRightToLeft("ff-Adlm"), "Fulah in Adlam")
        assertFalse(isRightToLeft("ff"), "Fulah defaults to Latin")
        assertFalse(isRightToLeft("ku"), "Kurdish defaults to Latin-script Kurmanji")
        assertTrue(isRightToLeft("ku-Arab"), "Kurdish in Arabic script")
    }

    @Test
    fun `a script subtag can also make a right-to-left language left-to-right`() {
        assertFalse(isRightToLeft("ar-Latn"), "romanised Arabic is written left-to-right")
    }

    @Test
    fun `a full tag with script and region is read correctly`() {
        assertTrue(isRightToLeft("uz-Arab-AF"))
        assertFalse(isRightToLeft("uz-Latn-UZ"))
        assertFalse(isRightToLeft("zh-Hans-CN"))
    }

    @Test
    fun `an underscore separator is accepted as well as a hyphen`() {
        // Locale.toString() uses underscores where toLanguageTag() uses hyphens.
        assertTrue(isRightToLeft("ar_EG"))
        assertTrue(isRightToLeft("az_Arab_IR"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(isRightToLeft("AR"))
        assertTrue(isRightToLeft("az-ARAB"))
        assertTrue(isRightToLeft("Az-arab"))
    }

    @Test
    fun `an unrecognised or malformed tag is reported left-to-right`() {
        // The safe direction to be wrong in: an unexpected language renders in the majority layout
        // rather than mirrored.
        assertFalse(isRightToLeft(""))
        assertFalse(isRightToLeft("-"))
        assertFalse(isRightToLeft("und"))
        assertFalse(isRightToLeft("qqq"))
    }

    @Test
    fun `the single-argument factory derives the direction`() {
        assertEquals(AppLanguage(code = "ar", isLtr = false), AppLanguage("ar"))
        assertEquals(AppLanguage(code = "en", isLtr = true), AppLanguage("en"))
        assertEquals(AppLanguage(code = "az-Arab", isLtr = false), AppLanguage("az-Arab"))
    }

    @Test
    fun `an explicit direction still wins over the derived one`() {
        // The escape hatch for a language this module gets wrong for a consumer's purposes.
        assertTrue(AppLanguage(code = "ar", isLtr = true).isLtr)
    }
}
