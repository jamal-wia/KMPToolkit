package io.github.jamal_wia.kmptoolkit.platform.url

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbsoluteUrlTest {

    @Test
    fun `accepts an https url`() {
        assertTrue(isAbsoluteUrl("https://example.com/a?b=c#d"))
    }

    @Test
    fun `accepts non-http schemes`() {
        assertTrue(isAbsoluteUrl("mailto:someone@example.com"))
        assertTrue(isAbsoluteUrl("tel:+15551234567"))
        assertTrue(isAbsoluteUrl("myapp://open/thing"))
    }

    @Test
    fun `accepts a scheme containing the punctuation the RFC allows`() {
        assertTrue(isAbsoluteUrl("x-custom.scheme+v2://host"))
    }

    @Test
    fun `rejects an empty or blank string`() {
        assertFalse(isAbsoluteUrl(""))
        assertFalse(isAbsoluteUrl("   "))
    }

    @Test
    fun `rejects a relative path`() {
        assertFalse(isAbsoluteUrl("/help/privacy"))
        assertFalse(isAbsoluteUrl("privacy.html"))
    }

    @Test
    fun `rejects a bare host with no scheme`() {
        assertFalse(isAbsoluteUrl("example.com"))
        assertFalse(isAbsoluteUrl("www.example.com/path"))
    }

    @Test
    fun `rejects a protocol-relative url`() {
        assertFalse(isAbsoluteUrl("//example.com/path"))
    }

    @Test
    fun `rejects a scheme that does not start with a letter`() {
        assertFalse(isAbsoluteUrl("1http://example.com"))
        assertFalse(isAbsoluteUrl("-http://example.com"))
    }

    @Test
    fun `rejects a scheme with nothing after it`() {
        assertFalse(isAbsoluteUrl("https:"))
    }

    @Test
    fun `rejects a leading colon`() {
        assertFalse(isAbsoluteUrl(":https://example.com"))
    }

    @Test
    fun `rejects a scheme containing a space`() {
        assertFalse(isAbsoluteUrl("ht tps://example.com"))
    }
}
