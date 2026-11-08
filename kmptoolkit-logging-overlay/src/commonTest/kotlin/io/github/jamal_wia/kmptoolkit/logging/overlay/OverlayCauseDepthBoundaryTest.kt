package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documented limit, at its edge: "A throwable whose cause chain is too deep to render (more than 64 nested
 * causes) is recorded as its first 64 causes, without stack frames." Up to 64 causes the record is the full
 * `stackTraceToString()`; from 65 it is the first 64 causes, no frames, and the rest only counted.
 */
class OverlayCauseDepthBoundaryTest {

    private fun chainWithCauses(causes: Int): Throwable {
        var chain: Throwable = RuntimeException("c0")
        repeat(causes - 1) { chain = RuntimeException("c${it + 1}", chain) }
        return if (causes == 0) RuntimeException("root") else RuntimeException("root", chain)
    }

    private fun textOf(throwable: Throwable): String {
        val state = LogOverlayState()
        state.asLogSink().log(LogLevel.ERROR, "T", "boom", throwable)
        return state.records.value.single().throwableText.orEmpty()
    }

    @Test
    fun `up to sixty four causes the record is the full stack trace`() {
        for (causes in listOf(0, 1, 2, 63, 64)) {
            val chain: Throwable = chainWithCauses(causes)
            assertEquals(chain.stackTraceToString(), textOf(chain), "$causes causes")
        }
    }

    @Test
    fun `from sixty five causes the record is the first sixty four causes without frames`() {
        for (causes in listOf(65, 66, 100, 1_000)) {
            val text: String = textOf(chainWithCauses(causes))
            val lines: List<String> = text.lines()
            assertTrue(lines.first().endsWith("root"), "$causes causes: first line '${lines.first()}'")
            assertEquals(64, lines.count { it.startsWith("Caused by: ") }, "$causes causes: shown causes")
            assertFalse(text.contains("\tat "), "$causes causes: frames rendered")
            val rest: Int = causes - 64
            assertTrue(lines.last().contains("$rest more causes not shown"), "$causes causes: last line '${lines.last()}'")
        }
    }
}
