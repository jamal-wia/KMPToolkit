package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 2, candidate defects.
 *
 * Group boss2-S1: the delivered-but-unconfirmed memory ([DeliveredUnconfirmed]) is forgotten by a withdrawal that did
 * not remove the row for good: one whose enclosing transaction rolled back (the cancel KDoc: "the row is restored and
 * stays queued"), and one whose delete and check read both failed (the cancel KDoc: "the attempt and upload are stopped
 * with the row possibly still queued"). The row, the very one whose effect already ran, is then run again by the same
 * engine. Guarantee broken: "An effect whose handler succeeded but whose row could not be deleted is not run again by the
 * same process" (UploaderEngine KDoc, guide 03). Before 4.1.0 the per-engine memory was only pruned by a pass that no
 * longer saw the row, so the rolled-back row kept its protection.
 *
 * Group boss2-S2: close() reaches a drain() its owner called only through a watcher coroutine that must first be
 * dispatched, while it cancels the engine's own pass at once. An answer that the attempt gives after close() returned,
 * before the watcher ran, is recorded on a manual drain and not on a started engine (close KDoc: "An attempt running at
 * close, also one of a drain() you called yourself, is cancelled and records nothing except what already happened";
 * drain KDoc: "stops it like the engine's own pass").
 */
class EngineBossRound2SuspectsAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Fails the next [deleteFailures] deletes and the next [readFailures] point reads: a locked database. */
    private class FlakyStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        var readFailures: Int = 0
        var readsBeforeFailing: Int = 0
        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }

        override suspend fun getById(id: String): UploaderItem? {
            if (readsBeforeFailing > 0) {
                readsBeforeFailing--
                return inner.getById(id)
            }
            if (readFailures > 0) {
                readFailures--
                throw IllegalStateException("database is locked")
            }
            return inner.getById(id)
        }
    }

    private class Rollback : RuntimeException("the consumer's transaction rolled back")

    /** A consumer transaction over the in-memory store: restores every row when its block throws. */
    private class RollingBackRunner(private val inner: TestUploaderStore) : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R {
            val before: List<UploaderItem> = inner.items
            try {
                return block()
            } catch (e: Throwable) {
                inner.items.forEach { inner.deleteById(it.id) }
                before.forEach { inner.insertKeep(it) }
                throw e
            }
        }
    }

    private suspend fun TestScope.deliveredButUnconfirmed(
        store: FlakyStore,
        inner: TestUploaderStore,
        handler: TestHandler,
        engine: UploaderEngine,
        tag: String? = null,
        uniqueKey: String? = null,
    ): UploaderItem {
        val id: String = engine.enqueue(handler, "send-message", uniqueKey = uniqueKey, tag = tag)!!
        store.deleteFailures = 1
        engine.drain()
        runCurrent()
        assertEquals(1, handler.payloads.size, "setup: the effect ran once")
        return assertNotNull(inner.find(id), "setup: its confirming delete failed")
    }

    @Test
    fun `boss2-S1a a cancel rolled back by its transaction does not run the delivered effect again`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val handler = TestHandler()
            val runner = RollingBackRunner(inner)
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("b2s1a")::next)
            val row: UploaderItem = deliveredButUnconfirmed(store, inner, handler, engine)

            assertFailsWith<Rollback> {
                runner.inTransaction {
                    engine.cancel(row.id)
                    throw Rollback()
                }
            }
            assertEquals(row, inner.find(row.id), "setup: the rollback restored the very row")

            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "the restored row of a delivered effect ran its handler again in the same process")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S1b a wipe by tag rolled back by its transaction does not run the delivered effect again`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val handler = TestHandler()
            val runner = RollingBackRunner(inner)
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("b2s1b")::next)
            val row: UploaderItem = deliveredButUnconfirmed(store, inner, handler, engine, tag = "account-1")

            assertFailsWith<Rollback> {
                runner.inTransaction {
                    engine.cancelByTag("account-1")
                    throw Rollback()
                }
            }
            assertEquals(row, inner.find(row.id), "setup: the rollback restored the very row")

            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "the restored row of a delivered effect ran its handler again in the same process")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S1c a REPLACE rolled back by its transaction does not run the superseded delivered effect again`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(
                store, listOf(handler), scopeOn(parent), clock,
                transactionRunner = RollingBackRunner(inner), idGenerator = SequentialIds("b2s1c")::next,
            )
            val row: UploaderItem = deliveredButUnconfirmed(store, inner, handler, engine, uniqueKey = "draft-9")

            // The consumer's domain write in the same transaction fails after the replacement: everything rolls back.
            val failingRunner = RollingBackRunner(inner)
            val throwingEngine: UploaderEngine = engine
            assertFailsWith<Rollback> {
                failingRunner.inTransaction {
                    throwingEngine.enqueue(handler, "send-message-v2", uniqueKey = "draft-9", conflictPolicy = ConflictPolicy.REPLACE)
                    throw Rollback()
                }
            }
            assertEquals(listOf(row), inner.items, "setup: the rollback restored only the superseded row")

            clock.millis += 60_000L
            engine.drain()
            assertEquals(listOf("send-message"), handler.payloads, "the restored row of a delivered effect ran its handler again in the same process")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S1d a cancel whose delete and check read fail does not run the delivered effect again`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyStore(inner)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock, idGenerator = SequentialIds("b2s1d")::next)
            val row: UploaderItem = deliveredButUnconfirmed(store, inner, handler, engine)

            store.deleteFailures = 1
            store.readsBeforeFailing = 1 // cancel's own first read of the row succeeds; the check read after the failed delete fails
            store.readFailures = 1
            assertFailsWith<IllegalStateException> { engine.cancel(row.id) }
            assertEquals(row, inner.find(row.id), "setup: the failed cancel left the row queued")

            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "a failed cancel made the engine run a delivered effect again in the same process")
        } finally {
            parent.cancel()
        }
    }

    private fun dropAfterRelease(release: CompletableDeferred<Unit>, answer: AttemptResult): TestHandler =
        TestHandler(onExecute = { _, _ ->
            release.await()
            answer
        })

    /** Control for boss2-S2: the started engine's attempt, resumed in the same step as close(), records nothing. */
    @Test
    fun `boss2-S2 control - a started engine records no drop answered right after close`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            val handler = dropAfterRelease(release, AttemptResult.Drop("no longer wanted"))
            val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2c")::next)
            val id: String = engine.enqueue(handler, "x")!!
            engine.start()
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the attempt is running")
            release.complete(Unit) // the handler's answer is on its way
            engine.close()
            runCurrent()
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "the started engine recorded a drop after close")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S2a a manual drain records no drop answered right after close`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            val handler = dropAfterRelease(release, AttemptResult.Drop("no longer wanted"))
            val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2a")::next)
            val id: String = engine.enqueue(handler, "x")!!
            val drain: Job = launch { engine.drain() }
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the attempt is running")
            release.complete(Unit)
            engine.close()
            runCurrent()
            drain.join()
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "the closed engine's manual drain dropped the row after close() returned")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S2b a manual drain charges no retry answered right after close`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            val handler = dropAfterRelease(release, AttemptResult.Retry(RuntimeException("socket closed")))
            val engine: UploaderEngine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2b")::next)
            val id: String = engine.enqueue(handler, "x")!!
            val drain: Job = launch { engine.drain() }
            runCurrent()
            release.complete(Unit)
            engine.close()
            runCurrent()
            drain.join()
            assertEquals(0, inner.find(id)?.attempts, "the closed engine's manual drain charged an attempt after close() returned")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S2c a handler that closes its engine during a manual drain has its retry ignored as on a started engine`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close()
                AttemptResult.Retry(RuntimeException("signed out"))
            })
            engine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2d")::next)
            val id: String = engine.enqueue(handler, "x")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            assertEquals(0, inner.find(id)?.attempts, "the manual drain charged the retry of an attempt whose engine closed under it")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S2 control - a handler that closes its started engine has its retry ignored`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close()
                AttemptResult.Retry(RuntimeException("signed out"))
            })
            engine = testEngine(inner, listOf(handler), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2e")::next)
            val id: String = engine.enqueue(handler, "x")!!
            engine.start()
            runCurrent()
            assertEquals(1, handler.payloads.size)
            assertEquals(0, inner.find(id)?.attempts, "the started engine charged the retry of an attempt whose engine closed under it")
        } finally {
            parent.cancel()
        }
    }

    /** A handler of another type whose every attempt fails: one drain leaves a backoff alarm armed in the engine scope. */
    private fun alwaysFailing(): TestHandler =
        TestHandler(type = "boss2.failing", onExecute = { _, _ -> AttemptResult.Retry(RuntimeException("offline")) })

    @Test
    fun `boss2-S2d a manual drain with a backoff alarm armed records no drop answered right after close`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val release = CompletableDeferred<Unit>()
            val handler = dropAfterRelease(release, AttemptResult.Drop("no longer wanted"))
            val failing: TestHandler = alwaysFailing()
            val engine: UploaderEngine = testEngine(inner, listOf(handler, failing), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2f")::next)
            engine.enqueue(failing, "f")!!
            engine.drain() // the failing item backs off: an alarm is armed in the engine's scope
            val id: String = engine.enqueue(handler, "x")!!
            val drain: Job = launch { engine.drain() }
            runCurrent()
            assertEquals(1, handler.payloads.size, "setup: the attempt is running")
            release.complete(Unit) // the handler's answer is on its way
            engine.close()
            runCurrent()
            drain.join()
            assertEquals(UploaderItemState.PENDING, inner.find(id)?.state, "the closed engine's manual drain dropped the row after close() returned")
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `boss2-S2e a handler that closes its engine with a backoff alarm armed has its retry ignored on a manual drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            lateinit var engine: UploaderEngine
            val handler = TestHandler(onExecute = { _, _ ->
                engine.close()
                AttemptResult.Retry(RuntimeException("signed out"))
            })
            val failing: TestHandler = alwaysFailing()
            engine = testEngine(inner, listOf(handler, failing), scopeOn(parent), TestClock(1_000L), idGenerator = SequentialIds("b2s2g")::next)
            engine.enqueue(failing, "f")!!
            engine.drain()
            val id: String = engine.enqueue(handler, "x")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            assertEquals(0, inner.find(id)?.attempts, "the manual drain charged the retry of an attempt whose engine closed under it")
        } finally {
            parent.cancel()
        }
    }
}
