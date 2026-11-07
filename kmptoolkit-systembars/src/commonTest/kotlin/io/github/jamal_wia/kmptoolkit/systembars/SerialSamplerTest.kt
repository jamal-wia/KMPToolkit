package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
/** Samples never overlap: a second one starts only when the first, readback included, is done. */
class SerialSamplerTest {

    private val sampler = SerialSampler()
    private val events: MutableList<String> = mutableListOf()

    @Test
    fun `a second sample waits for the first one's pending readback`() = runTest {
        val readback = CompletableDeferred<Unit>()
        launch { sampler.run { events += "first start"; readback.await(); events += "first end" } }
        launch { sampler.run { events += "second start" } }
        runCurrent()

        assertEquals(listOf("first start"), events, "the second sample re-recorded under a pending read")
        readback.complete(Unit)
        runCurrent()
        assertEquals(listOf("first start", "first end", "second start"), events)
    }

    @Test
    fun `a failing sample does not block the next one`() = runTest {
        assertFailsWith<IllegalStateException> { sampler.run { error("readback failed") } }

        assertEquals("ok", sampler.run { "ok" })
    }

    @Test
    fun `a cancelled waiter leaves and the lock is still released in order`() = runTest {
        val readback = CompletableDeferred<Unit>()
        launch { sampler.run { readback.await() } }
        runCurrent()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { sampler.run { events += "cancelled ran" } }
        runCurrent()
        waiting.cancel()
        launch { sampler.run { events += "third" } }
        readback.complete(Unit)
        runCurrent()

        assertEquals(listOf("third"), events)
    }
}
