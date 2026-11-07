package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * How the iOS delegate accumulates and converts what the server answered: the cap at the engine's
 * ceiling plus one byte, a single conversion, and `allHeaderFields` entries that are not strings dropped.
 */
class UploadResponseCaptureIosTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun dataOf(vararg values: Int): NSData {
        if (values.isEmpty()) return NSData()
        val bytes = ByteArray(values.size) { values[it].toByte() }
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun dataOfSize(size: Int, fill: Int = 7): NSData {
        val bytes = ByteArray(size) { fill.toByte() }
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

    @Test
    fun `chunks are joined in order`() {
        val buffer = ResponseBodyBuffer(ceiling = 100)

        buffer.append(dataOf(1, 2))
        buffer.append(dataOf(3))
        buffer.append(dataOf(4, 5))

        val (body: ByteArray, cut: Boolean) = buffer.toBodyAndCut()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), body)
        assertFalse(cut)
    }

    @Test
    fun `nothing received is an empty body that is not cut`() {
        val (body: ByteArray, cut: Boolean) = ResponseBodyBuffer(ceiling = 10).toBodyAndCut()

        assertEquals(0, body.size)
        assertFalse(cut)
    }

    @Test
    fun `a body of exactly the ceiling is not cut`() {
        val buffer = ResponseBodyBuffer(ceiling = 10)

        buffer.append(dataOfSize(10))

        val (body: ByteArray, cut: Boolean) = buffer.toBodyAndCut()
        assertEquals(10, body.size)
        assertFalse(cut)
    }

    @Test
    fun `a body one byte over the ceiling is cut to it and flagged`() {
        val buffer = ResponseBodyBuffer(ceiling = 10)

        buffer.append(dataOfSize(11))

        val (body: ByteArray, cut: Boolean) = buffer.toBodyAndCut()
        assertEquals(10, body.size)
        assertTrue(cut)
    }

    @Test
    fun `chunks past the ceiling are not kept`() {
        val buffer = ResponseBodyBuffer(ceiling = 10)

        buffer.append(dataOfSize(6, fill = 1))
        buffer.append(dataOfSize(6, fill = 2))
        buffer.append(dataOfSize(1_000, fill = 3))

        val (body: ByteArray, cut: Boolean) = buffer.toBodyAndCut()
        assertContentEquals(byteArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 2), body)
        assertTrue(cut)
    }

    @Test
    fun `the response carries the status and the body and keeps only string headers`() {
        val buffer = ResponseBodyBuffer(ceiling = 100)
        buffer.append(dataOf(104, 105))
        val fields: Map<Any?, *> = mapOf("Location" to "/jobs/1", 5 to "number key", "X-Count" to 7, null to "null key")

        val response: UploadResponse = uploadResponseOf(202, fields, buffer)

        assertEquals(202, response.statusCode)
        assertEquals("hi", response.body().decodeToString())
        assertEquals(mapOf("location" to "/jobs/1"), response.headers)
        assertFalse(response.isBodyTruncated)
    }

    @Test
    fun `an oversized body reaches the response flagged as truncated`() {
        val buffer = ResponseBodyBuffer(ceiling = 4)
        buffer.append(dataOfSize(9))

        val response: UploadResponse = uploadResponseOf(200, null, buffer)

        assertEquals(4, response.body().size)
        assertTrue(response.isBodyTruncated)
        assertTrue(response.headers.isEmpty())
    }
}
