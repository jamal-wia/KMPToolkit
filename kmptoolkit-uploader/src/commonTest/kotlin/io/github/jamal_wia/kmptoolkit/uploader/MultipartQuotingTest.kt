package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

class MultipartQuotingTest {

    @Test
    fun `a quote and both line break characters are percent-escaped and nothing else changes`() {
        assertEquals("a%22b%0D%0Ac%0Ad%0De", multipartQuotedValue("a\"b\r\nc\nd\re"))
    }

    @Test
    fun `a plain name with a percent sign and non-ascii text passes through untouched`() {
        assertEquals("photo 100%.jpg", multipartQuotedValue("photo 100%.jpg"))
        assertEquals("фото", multipartQuotedValue("фото"))
        assertEquals("", multipartQuotedValue(""))
    }

    @Test
    fun `an escaped name cannot contain a line break or a bare quote`() {
        val escaped: String = multipartQuotedValue("x\"\r\nX-Injected: 1\r\n\r\nsmuggled\"")

        assertEquals(false, escaped.contains('\r') || escaped.contains('\n') || escaped.contains('"'))
    }

    @Test
    fun `a content type loses its line breaks and keeps the rest`() {
        assertEquals("text/plainX-Injected: 1", multipartHeaderValue("text/plain\r\nX-Injected: 1"))
        assertEquals("image/png; charset=utf-8", multipartHeaderValue("image/png; charset=utf-8"))
    }
}
