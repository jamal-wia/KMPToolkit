package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The value type [UploadResponse], from its KDoc: headers, the body copy, equality and what `toString` hides. */
class UploadResponseTest {

    @Test
    fun `a header is found whatever the case of the name asked for`() {
        val response = UploadResponse(201, mapOf("Content-Type" to "application/json"), ByteArray(0), false)

        assertEquals("application/json", response.header("content-type"))
        assertEquals("application/json", response.header("CONTENT-TYPE"))
        assertEquals("application/json", response.header("Content-Type"))
        assertNull(response.header("etag"))
    }

    @Test
    fun `the headers are stored with lowercase names`() {
        val response = UploadResponse(200, mapOf("Location" to "/jobs/7", "X-Request-Id" to "abc"), ByteArray(0), false)

        assertEquals(mapOf("location" to "/jobs/7", "x-request-id" to "abc"), response.headers)
    }

    @Test
    fun `names that differ only in case are merged and their values joined`() {
        val response = UploadResponse(
            200,
            linkedMapOf("Vary" to "Accept", "vary" to "Origin", "VARY" to "Cookie"),
            ByteArray(0),
            false,
        )

        assertEquals(mapOf("vary" to "Accept, Origin, Cookie"), response.headers)
    }

    @Test
    fun `changing the map the response was built from does not change the response`() {
        val source: MutableMap<String, String> = mutableMapOf("a" to "1")
        val response = UploadResponse(200, source, ByteArray(0), false)

        source["b"] = "2"
        source["a"] = "changed"

        assertEquals(mapOf("a" to "1"), response.headers)
    }

    @Test
    fun `the body is copied on the way in and on every read`() {
        val source: ByteArray = byteArrayOf(1, 2, 3)
        val response = UploadResponse(200, emptyMap(), source, false)

        source[0] = 99
        val first: ByteArray = response.body()
        first[1] = 99
        val second: ByteArray = response.body()

        assertContentEquals(byteArrayOf(1, 2, 3), second)
        assertTrue(first !== second, "each read is a fresh array")
    }

    @Test
    fun `no body is an empty array and never null`() {
        val response = UploadResponse(204, emptyMap(), ByteArray(0), false)

        assertEquals(0, response.body().size)
    }

    @Test
    fun `toString says what is there but never prints headers or body`() {
        val response = UploadResponse(
            statusCode = 201,
            headers = mapOf("authorization" to "Bearer SECRET-TOKEN", "x-a" to "1", "x-b" to "2"),
            body = "SECRET-BODY-TEXT".encodeToByteArray(),
            isBodyTruncated = false,
        )

        val text: String = response.toString()

        assertEquals("UploadResponse(statusCode=201, headers=3, body=16 B, truncated=false)", text)
        assertFalse("SECRET" in text)
    }

    @Test
    fun `equality is by content`() {
        val a = UploadResponse(200, mapOf("A" to "1"), byteArrayOf(1, 2), false)
        val same = UploadResponse(200, mapOf("a" to "1"), byteArrayOf(1, 2), false)

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, UploadResponse(201, mapOf("a" to "1"), byteArrayOf(1, 2), false))
        assertNotEquals(a, UploadResponse(200, mapOf("a" to "2"), byteArrayOf(1, 2), false))
        assertNotEquals(a, UploadResponse(200, mapOf("a" to "1"), byteArrayOf(1, 3), false))
        assertNotEquals(a, UploadResponse(200, mapOf("a" to "1"), byteArrayOf(1, 2), true))
    }

    // --- The engine's enforcement of the limit ---------------------------------------------------

    @Test
    fun `a body within the limit is untouched and not truncated`() {
        val response = UploadResponse(200, emptyMap(), ByteArray(4), false)

        assertSame(response, response.enforced(limit = 4))
        assertFalse(response.enforced(limit = 4).isBodyTruncated)
    }

    @Test
    fun `a body over the limit is cut to it and flagged`() {
        val response = UploadResponse(200, mapOf("k" to "v"), byteArrayOf(1, 2, 3, 4, 5), false)

        val enforced: UploadResponse = response.enforced(limit = 3)

        assertContentEquals(byteArrayOf(1, 2, 3), enforced.body())
        assertTrue(enforced.isBodyTruncated)
        assertEquals(mapOf("k" to "v"), enforced.headers)
    }

    @Test
    fun `limit zero with a non-empty body is truncated with the headers kept`() {
        val response = UploadResponse(200, mapOf("location" to "/jobs/7"), byteArrayOf(1), false)

        val enforced: UploadResponse = response.enforced(limit = 0)

        assertEquals(0, enforced.body().size)
        assertTrue(enforced.isBodyTruncated)
        assertEquals("/jobs/7", enforced.header("location"))
    }

    @Test
    fun `limit zero with an empty body is not truncated`() {
        assertFalse(UploadResponse(204, emptyMap(), ByteArray(0), false).enforced(limit = 0).isBodyTruncated)
    }

    @Test
    fun `a flag set by the transport survives a limit the body fits in`() {
        val response = UploadResponse(200, emptyMap(), ByteArray(2), isBodyTruncated = true)

        assertTrue(response.enforced(limit = 10).isBodyTruncated)
    }

    @Test
    fun `headers beyond the cap are dropped and the rest kept in order`() {
        val big: String = "x".repeat(MAX_RESPONSE_HEADER_CHARS)
        val response = UploadResponse(200, linkedMapOf("a" to "1", "big" to big, "z" to "2"), ByteArray(0), false)

        val enforced: UploadResponse = response.enforced(limit = 10)

        assertEquals(mapOf("a" to "1"), enforced.headers)
    }
}
