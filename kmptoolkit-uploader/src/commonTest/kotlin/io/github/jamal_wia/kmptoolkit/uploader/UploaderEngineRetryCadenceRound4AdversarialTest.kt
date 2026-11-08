package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 4 (contract-lawyer): "A drain pass that fails as a whole retries itself: after `minAlarmDelay`, then with a
 * delay that doubles on every consecutive failure, at most `heartbeatInterval`. A pass that succeeds resets it."
 */
class UploaderEngineRetryCadenceRound4AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private class FlakyReads(private val inner: TestUploaderStore, private val scope: TestScope) : UploaderStore by inner {
        var failing = true
        val readTimes: MutableList<Long> = mutableListOf()
        override suspend fun getAllActive(): List<UploaderItem> {
            readTimes += scope.testScheduler.currentTime
            if (failing) throw IllegalStateException("database is locked")
            return inner.getAllActive()
        }
    }

    @Test
    fun `a failed pass retries after the minimum alarm delay and then doubles up to the heartbeat and a good pass resets it`() =
        runTest {
            val store = FlakyReads(TestUploaderStore(), this)
            val engine: UploaderEngine = createUploaderEngine(
                store = store,
                handlers = listOf(TestHandler()),
                scope = backgroundScope,
                config = UploaderConfig(heartbeatInterval = 10.seconds, minAlarmDelay = 50.milliseconds),
                clock = TestClock(1_000L),
                idGenerator = SequentialIds()::next,
            )
            try {
                engine.start()
                runCurrent()
                advanceTimeBy(7.seconds)
                val gaps: List<Long> = store.readTimes.zipWithNext { a, b -> b - a }.take(7)

                assertEquals(listOf(50L, 100L, 200L, 400L, 800L, 1_600L, 3_200L), gaps, "retry cadence of consecutive failed passes: ${store.readTimes}")

                // a pass that succeeds resets the cadence: the next failure starts again at the minimum
                store.failing = false
                engine.trigger()
                runCurrent()
                val afterGood: Int = store.readTimes.size
                store.failing = true
                engine.trigger()
                runCurrent()
                advanceTimeBy(60.milliseconds)
                val sinceReset: List<Long> = store.readTimes.drop(afterGood)

                assertTrue(sinceReset.size >= 2, "expected the failed pass and a retry within 60 ms, saw $sinceReset")
                assertEquals(50L, sinceReset[1] - sinceReset[0], "the retry after a good pass did not restart at minAlarmDelay: $sinceReset")
            } finally {
                engine.close()
            }
        }

    @Test
    fun `consecutive failures never wait longer than the heartbeat`() = runTest {
        val store = FlakyReads(TestUploaderStore(), this)
        val engine: UploaderEngine = createUploaderEngine(
            store = store,
            handlers = listOf(TestHandler()),
            scope = backgroundScope,
            config = UploaderConfig(heartbeatInterval = 5.seconds, minAlarmDelay = 50.milliseconds),
            clock = TestClock(1_000L),
            idGenerator = SequentialIds()::next,
        )
        try {
            engine.start()
            runCurrent()
            advanceTimeBy(120.seconds)
            val gaps: List<Long> = store.readTimes.zipWithNext { a, b -> b - a }

            assertTrue(gaps.all { it <= 5_000L }, "a retry waited longer than the heartbeat: ${gaps.filter { it > 5_000L }}")
            assertTrue(store.readTimes.size >= 120 / 5, "a failing store was left alone for too long: ${store.readTimes.size} reads in 120 s")
        } finally {
            engine.close()
        }
    }
}
