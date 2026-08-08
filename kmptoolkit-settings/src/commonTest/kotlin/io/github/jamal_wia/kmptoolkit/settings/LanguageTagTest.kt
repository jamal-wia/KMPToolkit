package io.github.jamal_wia.kmptoolkit.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LanguageTagTest {

    @Test
    fun `a bare language subtag is kept as it is`() {
        assertEquals("en", LanguageTag("en").value)
    }

    @Test
    fun `a language and region are canonicalised to lowercase and uppercase`() {
        assertEquals("pt-BR", LanguageTag("PT-br").value)
        assertEquals("pt-BR", LanguageTag("pt-br").value)
        assertEquals("pt-BR", LanguageTag("PT-BR").value)
    }

    @Test
    fun `a script subtag is canonicalised to titlecase`() {
        assertEquals("zh-Hant-TW", LanguageTag("ZH-HANT-tw").value)
    }

    @Test
    fun `a numeric region subtag is kept as it is`() {
        assertEquals("es-419", LanguageTag("ES-419").value)
    }

    @Test
    fun `a variant subtag is lowercased`() {
        assertEquals("de-DE-1901", LanguageTag("de-de-1901").value)
    }

    @Test
    fun `tags that differ only in case are the same value`() {
        assertEquals(LanguageTag("pt-BR"), LanguageTag("PT-br"))
        assertEquals(LanguageTag("pt-BR").hashCode(), LanguageTag("PT-br").hashCode())
    }

    @Test
    fun `tags that differ in region are different values`() {
        assertNotEquals(LanguageTag("pt-BR"), LanguageTag("pt-PT"))
    }

    @Test
    fun `the primary subtag is exposed for a language-only match`() {
        assertEquals("pt", LanguageTag("pt-BR").language)
        assertEquals("zh", LanguageTag("zh-Hant-TW").language)
        assertEquals("en", LanguageTag("EN").language)
    }

    @Test
    fun `subtags inside an extension sequence stay lowercase`() {
        // "ca" here is a calendar key, not a region — length-based casing alone would return
        // "en-u-CA-gregory", a different string than the caller wrote.
        assertEquals("en-u-ca-gregory", LanguageTag("en-U-CA-Gregory").value)
        assertEquals("en-u-nu-latn", LanguageTag("en-u-nu-Latn").value)
    }

    @Test
    fun `subtags inside a private-use sequence stay lowercase`() {
        assertEquals("en-x-ab", LanguageTag("en-X-AB").value)
        assertEquals("en-x-abcd", LanguageTag("en-x-ABCD").value)
    }

    @Test
    fun `a region before an extension is still uppercased`() {
        assertEquals("pt-BR-u-ca-gregory", LanguageTag("PT-br-U-ca-GREGORY").value)
    }

    @Test
    fun `an eight-letter primary subtag is accepted`() {
        assertEquals("abcdefgh", LanguageTag("abcdefgh").value)
    }

    @Test
    fun `an eight-character trailing subtag is accepted and a longer one is not`() {
        assertEquals("en-abcdefgh", LanguageTag("en-abcdefgh").value)
        assertNull(LanguageTag.ofOrNull("en-abcdefghi"))
    }

    @Test
    fun `an empty tag is rejected`() {
        assertNull(LanguageTag.ofOrNull(""))
        assertFailsWith<IllegalArgumentException> { LanguageTag("") }
    }

    @Test
    fun `a blank tag is rejected`() {
        assertNull(LanguageTag.ofOrNull(" "))
        assertNull(LanguageTag.ofOrNull("  "))
    }

    @Test
    fun `a one-letter primary subtag is rejected`() {
        assertNull(LanguageTag.ofOrNull("e"))
    }

    @Test
    fun `a primary subtag longer than eight letters is rejected`() {
        assertNull(LanguageTag.ofOrNull("abcdefghi"))
    }

    @Test
    fun `a numeric primary subtag is rejected`() {
        assertNull(LanguageTag.ofOrNull("12"))
        assertNull(LanguageTag.ofOrNull("e1"))
    }

    @Test
    fun `an empty subtag is rejected`() {
        assertNull(LanguageTag.ofOrNull("en-"))
        assertNull(LanguageTag.ofOrNull("-en"))
        assertNull(LanguageTag.ofOrNull("en--US"))
    }

    @Test
    fun `an underscore-separated locale is rejected because it is not a language tag`() {
        assertNull(LanguageTag.ofOrNull("en_US"))
    }

    @Test
    fun `a display name with a space is rejected`() {
        assertNull(LanguageTag.ofOrNull("English (US)"))
        assertNull(LanguageTag.ofOrNull("en US"))
    }

    @Test
    fun `an unregistered but well-formed subtag is accepted because only syntax is checked`() {
        // "english" is not a registered language subtag, but it is syntactically one, and this
        // type deliberately does not carry the IANA registry. Whether an app can render a tag is
        // SettingsConfig.supportedLanguages' question, not this one.
        assertEquals("english", LanguageTag("English").value)
    }

    @Test
    fun `non-ascii letters are rejected because the platform APIs only speak ascii`() {
        assertNull(LanguageTag.ofOrNull("ру"))
        assertNull(LanguageTag.ofOrNull("en-ЯЯ"))
    }

    @Test
    fun `toString is the tag itself so it can be interpolated into a platform call`() {
        assertEquals("pt-BR", LanguageTag("PT-br").toString())
    }
}
