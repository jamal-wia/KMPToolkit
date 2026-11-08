package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertTrue

/** The read bound of a journal entry must stay above the largest entry the journal can write. */
class OutcomeEntryBoundTest {

    private fun largestEncoded(headers: Map<String, String>): Int {
        val response = UploadResponse(200, headers, ByteArray(MAX_RESPONSE_BODY_BYTES) { 1 }, true)
        val enforced: UploadResponse = response.enforced(MAX_RESPONSE_BODY_BYTES)
        return OutcomeCodec.encode(enforced, 0L, 1).size
    }

    /** Guard, no red-before state: pins the bound against a future cap change. */
    @Test
    fun `the largest entry the journal can write is below the read bound with four-byte header characters`() {
        // A surrogate pair is two characters and four UTF-8 bytes, the densest the character cap allows.
        val value: String = "😀".repeat(MAX_RESPONSE_HEADER_CHARS / 2 - 1)
        val size: Int = largestEncoded(mapOf("h" to value))
        assertTrue(size < MAX_ENCODED_ENTRY_BYTES, "encoded $size bytes against a bound of $MAX_ENCODED_ENTRY_BYTES")
    }

    @Test
    fun `the largest entry the journal can write is below the read bound with three-byte header characters`() {
        val value: String = "€".repeat(MAX_RESPONSE_HEADER_CHARS - 2)
        val size: Int = largestEncoded(mapOf("h" to value))
        assertTrue(size < MAX_ENCODED_ENTRY_BYTES, "encoded $size bytes against a bound of $MAX_ENCODED_ENTRY_BYTES")
    }

    @Test
    fun `the largest entry the journal can write is below the read bound with many one-character headers`() {
        val headers: Map<String, String> = (0 until MAX_RESPONSE_HEADER_CHARS / 2).associate { ('一' + it).toString() to "x" }
        val size: Int = largestEncoded(headers)
        assertTrue(size < MAX_ENCODED_ENTRY_BYTES, "encoded $size bytes against a bound of $MAX_ENCODED_ENTRY_BYTES")
    }
}
