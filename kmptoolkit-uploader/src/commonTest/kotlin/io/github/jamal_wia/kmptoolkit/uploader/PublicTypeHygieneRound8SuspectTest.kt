package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Round 8 (hostile consumer, public type hygiene): equals/hashCode/toString/copy of the public value types, bad
 * arguments of every public constructor, sealed and enum stability, the documented classification table.
 */
/** Round 8 SUSPECTS R8-S6 (request toString prints header values) and R8-S7 (response headers mutable through a cast). */
class PublicTypeHygieneRound8SuspectTest {

    private val token = "Bearer SECRET-TOKEN-4711"

    @Test
    fun `UploadResponse headers cannot be mutated through a cast to a mutable map`() {
        val response = UploadResponse(200, mapOf("a" to "1", "b" to "2", "c" to "3"), ByteArray(0), false)
        val hash: Int = response.hashCode()

        runCatching {
            @Suppress("UNCHECKED_CAST")
            (response.headers as MutableMap<String, String>)["a"] = "tampered"
        }

        assertEquals("1", response.header("a"), "the documented read-only headers were changed through a cast")
        assertEquals(hash, response.hashCode())
    }

    @Test
    fun `UploadRequest toString does not print a header value such as an authorization token`() {
        val request = UploadRequest(
            url = "https://example.com/upload",
            headers = mapOf("Authorization" to token),
            fields = listOf(UploadField.Text("comment", "SECRET-TEXT")),
        )

        val text: String = request.toString()

        assertFalse("SECRET-TOKEN" in text, "the header value is printed: $text")
    }

    @Test
    fun `UploadPreparation Proceed toString does not print the request's header values`() {
        val proceed = UploadPreparation.Proceed(
            UploadRequest("https://example.com", headers = mapOf("Authorization" to token), fields = emptyList()),
        )

        assertFalse("SECRET-TOKEN" in proceed.toString(), proceed.toString())
    }
}
