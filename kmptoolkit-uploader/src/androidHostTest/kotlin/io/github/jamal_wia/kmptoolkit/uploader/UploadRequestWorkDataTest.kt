package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.Data
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * The manual [Data] encoding [UploadRequest] round-trips through, since WorkManager's `Data` only
 * carries primitives and primitive arrays — no object graph, and this module takes on no
 * serialization dependency to bridge that.
 */
@RunWith(AndroidJUnit4::class)
class UploadRequestWorkDataTest {

    @Test
    fun `a request with only text fields round-trips`() {
        val request = UploadRequest(
            url = "https://example.com/upload",
            headers = mapOf("Authorization" to "Bearer token"),
            fields = listOf(UploadField.Text(name = "caption", value = "hello")),
        )

        val data: Data = request.toWorkData("item-1", engineWaitMillis = 5_000, connectTimeoutMillis = 1, readTimeoutMillis = 2)

        assertEquals("item-1", data.readItemId())
        assertEquals(5_000L, data.readEngineWaitMillis(0))
        assertEquals(1, data.readConnectTimeoutMillis(0))
        assertEquals(2, data.readReadTimeoutMillis(0))
        assertEquals(request, data.toUploadRequestOrNull())
    }

    @Test
    fun `a request with a file field round-trips`() {
        val request = UploadRequest(
            url = "https://example.com/upload",
            method = "PUT",
            fields = listOf(
                UploadField.File(name = "avatar", fileName = "photo.jpg", contentType = "image/jpeg", path = "/tmp/photo.jpg"),
            ),
        )

        val data: Data = request.toWorkData("item-2", engineWaitMillis = 1, connectTimeoutMillis = 1, readTimeoutMillis = 1)

        assertEquals(request, data.toUploadRequestOrNull())
    }

    @Test
    fun `mixed text and file fields keep their order`() {
        val request = UploadRequest(
            url = "https://example.com/upload",
            fields = listOf(
                UploadField.Text(name = "caption", value = "hi"),
                UploadField.File(name = "file", fileName = "a.bin", contentType = "application/octet-stream", path = "/a"),
                UploadField.Text(name = "tag", value = "x"),
            ),
        )

        val data: Data = request.toWorkData("item-3", 1, 1, 1)

        assertEquals(request.fields, data.toUploadRequestOrNull()?.fields)
    }

    @Test
    fun `no fields is a valid request`() {
        val request = UploadRequest(url = "https://example.com/upload", fields = emptyList())

        val data: Data = request.toWorkData("item-4", 1, 1, 1)

        assertEquals(request, data.toUploadRequestOrNull())
    }

    @Test
    fun `data not built by toWorkData decodes to null`() {
        val data: Data = Data.Builder().putString("unrelated", "value").build()

        assertNull(data.toUploadRequestOrNull())
        assertNull(data.readItemId())
    }

    @Test
    fun `absent optional values fall back to the given default`() {
        val data: Data = Data.EMPTY

        assertEquals(42L, data.readEngineWaitMillis(42L))
        assertEquals(7, data.readConnectTimeoutMillis(7))
        assertEquals(9, data.readReadTimeoutMillis(9))
    }
}
