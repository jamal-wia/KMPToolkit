package io.github.jamal_wia.kmptoolkit.uploader

import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 4 (resource-starvation): a closed engine, and everything its handlers capture, must become garbage while
 * the scope that owned it lives on. A strong reference left in the registry, in the parent job, in a pending alarm
 * or in a cancelled attempt would keep a store, a database handle and a screen alive for the life of the process.
 */
class UploaderEngineCollectionRound4AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class Heavy {
        @Suppress("unused")
        val ballast: ByteArray = ByteArray(1 shl 20)
    }

    private class LeakProbe(val engine: WeakReference<Any>, val heavy: WeakReference<Heavy>)

    private fun runAndClose(parent: Job, closeMidAttempt: Boolean, register: Boolean, closeIt: Boolean = true): LeakProbe = runBlocking {
        val heavy = Heavy()
        val running = CompletableDeferred<Unit>()
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 3_600_000L),
            onExecute = { _, payload ->
                heavy.ballast.size // captured
                if (closeMidAttempt && payload == "hang") {
                    running.complete(Unit)
                    delay(1_000_000L)
                }
                AttemptResult.Retry(null) // leaves a one-hour gate and an armed alarm behind
            },
        )
        val engine: UploaderEngine = createUploaderEngine(
            store = TestUploaderStore(),
            handlers = listOf(handler),
            scope = CoroutineScope(Dispatchers.Default + parent),
            clock = TestClock(1_000L),
            wakeScheduler = TestWakeScheduler(),
            idGenerator = SequentialIds()::next,
        )
        if (register) UploaderEngineRegistry.register(engine)
        engine.start()
        engine.enqueue(handler, if (closeMidAttempt) "hang" else "gated")
        withTimeout(10.seconds) {
            if (closeMidAttempt) running.await() else engine.awaitDrained(200L.let { kotlin.time.Duration.parse("0.2s") })
        }
        if (closeIt) engine.close()
        LeakProbe(WeakReference(engine), WeakReference(heavy))
    }

    private fun awaitCollected(probe: LeakProbe, what: String) {
        repeat(40) {
            System.gc()
            if (probe.engine.get() == null && probe.heavy.get() == null) return
            Thread.sleep(50)
        }
        assertNull(probe.engine.get(), "$what: the engine is still reachable after close")
        assertNull(probe.heavy.get(), "$what: something the handler captured is still reachable after close")
    }

    @Test
    fun `an idle closed engine with an armed alarm is collected while its parent scope lives`() {
        val parent = Job()
        try {
            awaitCollected(runAndClose(parent, closeMidAttempt = false, register = false), "idle")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `an engine closed in the middle of an attempt is collected while its parent scope lives`() {
        val parent = Job()
        try {
            awaitCollected(runAndClose(parent, closeMidAttempt = true, register = false), "mid-attempt")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a registered engine is collected once it is closed`() {
        val parent = Job()
        try {
            awaitCollected(runAndClose(parent, closeMidAttempt = false, register = true), "registered")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `control - an engine that is not closed stays reachable through its live parent scope`() {
        val parent = Job()
        try {
            val probe: LeakProbe = runAndClose(parent, closeMidAttempt = false, register = false, closeIt = false)
            repeat(5) {
                System.gc()
                Thread.sleep(50)
            }
            kotlin.test.assertNotNull(probe.engine.get(), "the collection check cannot tell a leak from garbage")
        } finally {
            parent.cancel()
        }
    }
}
