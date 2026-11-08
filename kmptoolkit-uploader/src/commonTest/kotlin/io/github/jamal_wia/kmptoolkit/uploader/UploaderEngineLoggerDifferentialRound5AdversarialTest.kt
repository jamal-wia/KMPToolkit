package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 5 (throwing loggers, D-37): the same long scenario, run once with a silent logger and once with a logger
 * that throws from every member (tag, isLoggable, log, with Errors and a CancellationException among the throws),
 * must leave the same queue and the same handler and transport calls behind. The engine's behaviour may not depend
 * on whether the sink is healthy on any path: drain, settle, withdraw, wipe, replace, lease expiry, orphan rows.
 */
class UploaderEngineLoggerDifferentialRound5AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class ExplodingLogger : Logger {
        private var n = 0
        private fun boom(): Nothing = when (n++ % 4) {
            0 -> throw IllegalStateException("crash reporter not initialised")
            1 -> throw NotImplementedError("no sink")
            2 -> throw CancellationException("sink was cancelled")
            else -> throw Error("a sink that fails with an Error")
        }

        override val tag: String get() = boom()
        override fun isLoggable(level: LogLevel): Boolean = boom()
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            // evaluates the message first, like a real sink formatting its line, then fails
            message()
            boom()
        }
    }

    private class Transport : UploadTransport {
        val events: MutableList<String> = mutableListOf()
        override val leaseMillis: Long = 5_000L
        override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
        override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
            events += "launch:$itemId:$isRehandOff"
        }

        override fun cancel(itemId: String) {
            events += "cancel:$itemId"
        }
    }

    private class UpHandler(transport: UploadTransport, val calls: MutableList<String>) : UploadHandler<String>(transport) {
        override val type: String = "r5.up"
        override val retryPolicy: RetryPolicy = FixedRetryPolicy(500L, GiveUpPolicy.ParkAfterAttempts(3))
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String {
            if (raw == "undecodable") throw CancellationException("decoder cancelled")
            return raw
        }

        override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation {
            calls += "prepare:$payload"
            return when (payload) {
                "drop" -> UploadPreparation.Drop("nothing to send")
                "park" -> UploadPreparation.Park("bad file")
                "throw" -> throw IllegalStateException("prepare failed")
                else -> UploadPreparation.Proceed(UploadRequest("https://example.com/u", fields = listOf(UploadField.Text("a", payload))))
            }
        }

        override suspend fun onDelivered(payload: String) {
            calls += "delivered:$payload"
        }

        override suspend fun onSettled(payload: String, attempts: Int, result: SettleResult) {
            calls += "settled:$payload:${result::class.simpleName}"
        }
    }

    private suspend fun scenario(scope: TestScope, logger: Logger): List<String> {
        val store = TestUploaderStore()
        val clock = TestClock(1_000_000L)
        val wake = TestWakeScheduler()
        val calls: MutableList<String> = mutableListOf()
        val transport = Transport()
        val up = UpHandler(transport, calls)
        val plain = TestHandler(
            type = "r5.plain",
            retryPolicy = FixedRetryPolicy(500L, GiveUpPolicy.DropAfterAttempts(4)),
            decode = { if (it == "bad") throw IllegalArgumentException("cannot decode") else it },
            onExecute = { ctx, payload ->
                calls += "exec:$payload:${ctx.attempts}:${ctx.wasDetached}"
                when (payload) {
                    "ok" -> AttemptResult.Success
                    "retry" -> AttemptResult.Retry(IllegalStateException("flaky"))
                    "park" -> AttemptResult.Park("no")
                    "drop" -> AttemptResult.Drop("gone")
                    "detach" -> AttemptResult.Detached(2_000L)
                    "boom" -> throw IllegalStateException("handler bug")
                    "leak" -> throw CancellationException("leaked timeout")
                    else -> AttemptResult.Success
                }
            },
        )
        val job = Job()
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(up, plain),
            scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + job),
            wakeScheduler = wake,
            clock = clock,
            logger = logger,
            idGenerator = SequentialIds()::next,
        )
        UploaderEngineRegistry.register(engine)
        try {
            val ids: MutableMap<String, String> = mutableMapOf()
            suspend fun put(h: UploaderHandler<String>, payload: String, key: String? = null, tag: String? = null, policy: ConflictPolicy = ConflictPolicy.KEEP) {
                engine.enqueue(h, payload, uniqueKey = key, tag = tag, conflictPolicy = policy)?.let { ids[payload] = it }
            }
            for (p in listOf("ok", "retry", "park", "drop", "detach", "boom", "leak", "bad")) put(plain, p, tag = "t")
            for (p in listOf("u1", "u2", "drop", "park", "throw", "undecodable")) put(up, p, tag = "u")
            // rows the engine cannot serve
            store.insertKeep(UploaderItem("orphan", "r5.nobody", "x", 1, null, null, null, UploaderItemState.PENDING, 0, 0L, 0L, null))
            store.insertKeep(UploaderItem("future", "r5.plain", "x", 9, null, null, null, UploaderItemState.PENDING, 0, 0L, 0L, null))
            engine.drain()
            clock.millis += 600; engine.drain()
            clock.millis += 600; engine.drain()
            // outcomes for the uploads
            UploadGateway.complete(ids.getValue("u1"), UploadResult.Completed(201), 10.milliseconds)
            UploadGateway.complete(ids.getValue("u2"), UploadResult.Completed(503), 10.milliseconds)
            UploadGateway.complete("missing", UploadResult.Completed(201), 10.milliseconds)
            ids["detach"]?.let { engine.settle(it, store.find(it)?.claim ?: 0L, SettleResult.Delivered) }
            engine.settle("missing", 0L, SettleResult.Failed(null))
            clock.millis += 10_000; engine.drain()
            put(up, "u3", key = "k", tag = "u")
            engine.drain()
            put(up, "u3b", key = "k", tag = "u", policy = ConflictPolicy.REPLACE)
            engine.drain()
            engine.cancel(ids.getValue("u2"))
            engine.cancel("never-existed")
            engine.cancelByTag("t")
            clock.millis -= 5_000_000L // wall clock steps back
            engine.drain()
            clock.millis += 9_000_000L
            engine.drain()
            engine.awaitDrained(10.milliseconds)
            return buildList {
                addAll(calls)
                addAll(transport.events)
                addAll(store.items.map { "${it.id}/${it.type}/${it.state}/a${it.attempts}/n${it.nextRunAtEpochMillis}/c${it.claim}/l${it.leaseUntilEpochMillis}/${it.lastError}" })
                add("wake:${wake.armed}")
            }
        } finally {
            engine.close()
            job.cancel()
        }
    }

    @Test
    fun `a sink that throws from every member changes nothing the engine does on any path`() = runTest(timeout = 60.seconds) {
        val quiet: List<String> = scenario(this, NoopLogger)
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        val loud: List<String> = scenario(this, ExplodingLogger())

        assertTrue(quiet.size > 20, "the scenario did real work: $quiet")
        assertEquals(quiet, loud)
    }
}
