package io.github.jamal_wia.kmptoolkit.smoke.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** The cleanup guard of the smoke (breaker round 1: m3). A failing cleanup must never hide the failure that came first. */
class GuardedTest {

    @Test
    fun `a successful body is followed by exactly one cleanup that is told it succeeded`() = runTest {
        val told: MutableList<Boolean> = mutableListOf()
        val result: String = guarded(body = { "done" }, cleanup = { succeeded: Boolean -> told += succeeded })
        assertEquals("done", result)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a failing body is followed by exactly one cleanup that is told it failed, and its failure is rethrown as it is`() = runTest {
        val told: MutableList<Boolean> = mutableListOf()
        val original: SmokeFailure = SmokeFailure("put failed")
        val thrown: Throwable = assertFailsWith<SmokeFailure> {
            guarded<Unit>(body = { throw original }, cleanup = { succeeded: Boolean -> told += succeeded })
        }
        assertSame(original, thrown)
        assertEquals(listOf(false), told)
    }

    @Test
    fun `a cleanup that fails after a failed body is attached to the original and does not replace it`() = runTest {
        val original: SmokeFailure = SmokeFailure("the first failure")
        val cleanupFailure: SmokeFailure = SmokeFailure("clear failed too")
        val thrown: Throwable = assertFailsWith<SmokeFailure> {
            guarded<Unit>(body = { throw original }, cleanup = { _: Boolean -> throw cleanupFailure })
        }
        assertSame(original, thrown, "the verdict names the cause, not the cleanup")
        // Compared by message: kotlinx.coroutines may hand back a stack-trace-recovered copy of an exception that crossed withContext.
        assertEquals(listOf(cleanupFailure.message), thrown.suppressed.map { it.message })
    }

    @Test
    fun `a cleanup that fails after a successful body is the failure`() = runTest {
        val cleanupFailure: SmokeFailure = SmokeFailure("the key item is still there")
        val thrown: SmokeFailure = assertFailsWith<SmokeFailure> {
            guarded(body = { 1 }, cleanup = { _: Boolean -> throw cleanupFailure })
        }
        assertEquals(cleanupFailure.message, thrown.message, "the cleanup's failure is the verdict when nothing failed before")
    }

    @Test
    fun `a body cancelled by its time limit still gets its cleanup, to completion`() = runTest {
        val steps: MutableList<String> = mutableListOf()
        assertFailsWith<CancellationException> {
            withTimeout(100L) {
                guarded<Unit>(
                    body = { delay(1_000L) },
                    cleanup = { succeeded: Boolean ->
                        // Suspension points inside the cleanup: they would throw at once in a cancelled coroutine.
                        steps += "start succeeded=$succeeded"
                        yield()
                        delay(10L)
                        steps += "end"
                    },
                )
            }
        }
        assertEquals(listOf("start succeeded=false", "end"), steps)
    }

    @Test
    fun `runAll runs every step even after a failure and throws the first with the others attached`() = runTest {
        val ran: MutableList<String> = mutableListOf()
        val first: SmokeFailure = SmokeFailure("first")
        val second: SmokeFailure = SmokeFailure("second")
        val thrown: Throwable = assertFailsWith<SmokeFailure> {
            runAll(
                { ran += "a"; throw first },
                { ran += "b"; throw second },
                { ran += "c" },
            )
        }
        assertEquals(listOf("a", "b", "c"), ran, "a failing step never skips a later one")
        assertSame(first, thrown)
        assertEquals(listOf(second.message), thrown.suppressed.map { it.message })
    }

    @Test
    fun `runAll with no failure throws nothing`() = runTest {
        val ran: MutableList<String> = mutableListOf()
        runAll({ ran += "a" }, { ran += "b" })
        assertEquals(listOf("a", "b"), ran)
    }
}
