package io.github.jamal_wia.kmptoolkit.platform.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class NamedFailure(message: String?) : RuntimeException(message)

class CrashHandlerTest {

    @Test
    fun `keeps the exception message when there is one`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure("boom"), "main", 42)

        assertEquals("boom", record.message)
    }

    @Test
    fun `falls back to the exception class name when the message is null`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure(null), "main", 42)

        assertEquals("NamedFailure", record.message)
    }

    @Test
    fun `falls back to the exception class name when the message is blank`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure("   "), "main", 42)

        assertEquals("NamedFailure", record.message)
    }

    @Test
    fun `carries the thread name and timestamp it was given`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure("boom"), "worker-3", 1234)

        assertEquals("worker-3", record.threadName)
        assertEquals(1234, record.timestampMs)
    }

    @Test
    fun `records a stack trace that names the exception`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure("boom"), "main", 42)

        assertTrue(
            "NamedFailure" in record.stackTrace,
            "stack trace should name the exception type: ${record.stackTrace}",
        )
    }

    @Test
    fun `survives an Error rather than an Exception`() {
        // Not StackOverflowError or OutOfMemoryError: those are JVM-only types and this test also
        // runs on Kotlin/Native.
        val record: CrashRecord = buildCrashRecord(Error("fatal"), "main", 42)

        assertEquals("fatal", record.message)
    }

    @Test
    fun `a record built from a crash survives the codec`() {
        val record: CrashRecord = buildCrashRecord(NamedFailure("boom"), "main", 42)

        assertEquals(record, decodeCrashRecord(encodeCrashRecord(record)))
    }
}
