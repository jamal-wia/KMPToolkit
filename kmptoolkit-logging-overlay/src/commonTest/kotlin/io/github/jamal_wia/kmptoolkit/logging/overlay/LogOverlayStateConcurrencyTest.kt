package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LogOverlayState] documents that [LogOverlayState.record] may be called from any thread. These
 * tests hammer it from `Dispatchers.Default`, which is genuinely multi-threaded on both the JVM and
 * Kotlin/Native, so a lost update or a torn list shows up as a wrong count rather than as a flake
 * that only CI sees.
 */
class LogOverlayStateConcurrencyTest {

    @Test
    fun `concurrent appends below the bound lose nothing`() = runTest {
        val state = LogOverlayState(maxRecords = WRITERS * PER_WRITER)

        appendConcurrently(state)

        assertEquals(WRITERS * PER_WRITER, state.records.value.size)
        assertEquals(
            (1..WRITERS * PER_WRITER).map(Int::toLong),
            state.records.value.map(LogRecord::id),
            "ids must be dense and increasing when nothing was evicted",
        )
    }

    @Test
    fun `concurrent appends past the bound still respect it`() = runTest {
        val state = LogOverlayState(maxRecords = BOUND)

        appendConcurrently(state)

        val records: List<LogRecord> = state.records.value
        assertEquals(BOUND, records.size)
        assertEquals(records.map(LogRecord::id).sorted(), records.map(LogRecord::id))
        assertEquals(records.map(LogRecord::id).distinct().size, records.size, "ids must stay unique")
    }

    @Test
    fun `concurrent appends and clears leave the buffer within its bound`() = runTest {
        val state = LogOverlayState(maxRecords = BOUND)

        withContext(Dispatchers.Default) {
            coroutineScope {
                val writers = List(WRITERS) { writer ->
                    async { repeat(PER_WRITER) { index -> state.record(LogLevel.INFO, "W$writer", "m$index") } }
                }
                val clearer = async { repeat(PER_WRITER) { state.clear() } }
                (writers + clearer).awaitAll()
            }
        }

        val records: List<LogRecord> = state.records.value
        assertTrue(records.size <= BOUND, "buffer overflowed its bound: ${records.size}")
        assertEquals(records.map(LogRecord::id).distinct().size, records.size, "ids must stay unique")
    }

    private suspend fun appendConcurrently(state: LogOverlayState) {
        withContext(Dispatchers.Default) {
            coroutineScope {
                List(WRITERS) { writer ->
                    async { repeat(PER_WRITER) { index -> state.record(LogLevel.INFO, "W$writer", "m$index") } }
                }.awaitAll()
            }
        }
    }

    private companion object {
        const val WRITERS = 8
        const val PER_WRITER = 250
        const val BOUND = 100
    }
}
