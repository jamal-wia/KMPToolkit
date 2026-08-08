package io.github.jamal_wia.kmptoolkit.platform.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashRecordCodecTest {

    private val record = CrashRecord(
        timestampMs = 1_700_000_000_000,
        threadName = "main",
        message = "boom",
        stackTrace = "at A\nat B",
    )

    @Test
    fun `round trips a record unchanged`() {
        assertEquals(record, decodeCrashRecord(encodeCrashRecord(record)))
    }

    @Test
    fun `encodes a multi-line stack trace onto exactly one line`() {
        val encoded: String = encodeCrashRecord(record)

        assertTrue('\n' !in encoded, "encoded record must not contain a line break: $encoded")
        assertTrue('\r' !in encoded, "encoded record must not contain a carriage return: $encoded")
    }

    @Test
    fun `round trips a message containing the field separator`() {
        val tabbed: CrashRecord = record.copy(message = "before\tafter")

        assertEquals(tabbed, decodeCrashRecord(encodeCrashRecord(tabbed)))
    }

    @Test
    fun `round trips a message containing the escape character`() {
        val escaped: CrashRecord = record.copy(message = """C:\path\\to""")

        assertEquals(escaped, decodeCrashRecord(encodeCrashRecord(escaped)))
    }

    @Test
    fun `round trips carriage returns and empty fields`() {
        val awkward: CrashRecord = record.copy(message = "", stackTrace = "a\r\nb")

        assertEquals(awkward, decodeCrashRecord(encodeCrashRecord(awkward)))
    }

    @Test
    fun `round trips a negative timestamp`() {
        val beforeEpoch: CrashRecord = record.copy(timestampMs = -1)

        assertEquals(beforeEpoch, decodeCrashRecord(encodeCrashRecord(beforeEpoch)))
    }

    @Test
    fun `rejects a blank line`() {
        assertNull(decodeCrashRecord(""))
    }

    @Test
    fun `rejects a line with too few fields`() {
        assertNull(decodeCrashRecord("123\tmain\tboom"))
    }

    @Test
    fun `rejects a line with too many fields`() {
        assertNull(decodeCrashRecord("123\tmain\tboom\ttrace\textra"))
    }

    @Test
    fun `rejects a line whose timestamp is not a number`() {
        assertNull(decodeCrashRecord("not-a-number\tmain\tboom\ttrace"))
    }

    @Test
    fun `rejects a line truncated in the middle of an escape`() {
        val truncated: String = encodeCrashRecord(record).let { encoded ->
            encoded.substring(0, encoded.indexOf('\\') + 1)
        }

        assertNull(decodeCrashRecord(truncated))
    }

    @Test
    fun `rejects a line with an unknown escape`() {
        assertNull(decodeCrashRecord("123\tmain\tbo\\xom\ttrace"))
    }
}
