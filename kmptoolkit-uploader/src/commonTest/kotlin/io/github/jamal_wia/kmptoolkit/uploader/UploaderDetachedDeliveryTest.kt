package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Detached delivery: claim under a lease, settle, or lose the claim when the lease expires.
 *
 * This is the part where a crash must neither lose an effect nor send it twice, so most of these
 * tests are shaped like crashes: an executor that vanishes, a settle that arrives late, a settle
 * that arrives twice, and a settle racing the drain's decision to re-hand the item.
 */
class UploaderDetachedDeliveryTest {

    @Test
    fun `detaching moves the item to in-flight under a lease without spending retry budget`() =
        runTest {
            val store = TestUploaderStore()
            val clock = TestClock(millis = 1_000L)
            val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 30_000) })
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
            engine.enqueue(handler, "p")

            engine.drain()

            val item: UploaderItem = assertNotNull(store.find("item-1"))
            assertEquals(UploaderItemState.IN_FLIGHT, item.state)
            assertEquals(31_000L, item.leaseUntilEpochMillis)
            assertEquals(0, item.attempts, "a hand-off is not a failure")
        }

    @Test
    fun `an unexpired lease keeps the drain off the item`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()

        clock.millis = 9_999L
        engine.drain()

        assertEquals(1, handler.attempts.size, "an unexpired claim must be left alone")
    }

    @Test
    fun `an expired lease makes the item attemptable again and flags the re-hand-off`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()

        clock.millis = 10_001L
        engine.drain()

        assertEquals(2, handler.attempts.size)
        assertTrue(!handler.attempts[0].wasDetached, "the first hand-off is not a re-hand-off")
        assertTrue(
            handler.attempts[1].wasDetached,
            "a re-hand-off must be flagged so the handler can rejoin rather than duplicate",
        )
        assertEquals(0, store.find("item-1")?.attempts, "re-handing spends no retry budget either")
    }

    @Test
    fun `a non-positive lease is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { AttemptResult.Detached(leaseMillis = 0) }
        assertFailsWith<IllegalArgumentException> { AttemptResult.Detached(leaseMillis = -1) }
    }

    @Test
    fun `settling as delivered deletes the item`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Delivered)

        assertNull(store.find("item-1"))
    }

    @Test
    fun `settling as dropped deletes the item`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Drop("the message was deleted"))

        assertNull(store.find("item-1"))
    }

    @Test
    fun `settling as parked keeps the item with its reason`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Park("rejected by the server"))

        val parked: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PARKED, parked.state)
        assertEquals("rejected by the server", parked.lastError)
        assertEquals(0L, parked.leaseUntilEpochMillis)
    }

    @Test
    fun `settling as failed spends one retry and returns the item to pending`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 500L)
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(delayMillis = 2_000L),
            onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Failed(IllegalStateException("upload broke")))

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state)
        assertEquals(1, item.attempts)
        assertEquals(2_500L, item.nextRunAtEpochMillis)
        assertEquals(0L, item.leaseUntilEpochMillis, "settling must release the claim")
        assertTrue(item.lastError.orEmpty().contains("upload broke"))
    }

    @Test
    fun `settling as failed can exhaust the give-up budget`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(
            retryPolicy = FixedRetryPolicy(
                delayMillis = 1L,
                giveUp = GiveUpPolicy.ParkAfterAttempts(maxAttempts = 1),
            ),
            onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) },
        )
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Failed())

        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
    }

    @Test
    fun `settling an unknown id is a no-op`() = runTest {
        val store = TestUploaderStore()
        val engine: UploaderEngine = testEngine(store, listOf(TestHandler()), backgroundScope)

        engine.settle("never-existed", claim = 1L, SettleResult.Delivered)

        assertEquals(emptyList(), store.deletedIds, "a settle for a missing row must touch nothing")
    }

    @Test
    fun `settling the same item twice deletes it once and then does nothing`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()

        val claim: Long = handler.attempts.single().claim
        engine.settle("item-1", claim, SettleResult.Delivered)
        engine.settle("item-1", claim, SettleResult.Delivered)

        assertEquals(listOf("item-1"), store.deletedIds, "the second settle must find nothing")
    }

    @Test
    fun `a superseded item's late settle cannot touch its replacement`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "old", uniqueKey = "u")
        engine.drain()
        engine.enqueue(handler, "new", uniqueKey = "u", conflictPolicy = ConflictPolicy.REPLACE)

        // The zombie executor reports on the id it was handed, which no longer exists.
        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Delivered)

        assertEquals(UploaderItemState.PENDING, store.find("item-2")?.state)
        assertEquals("new", store.find("item-2")?.payload)
    }

    @Test
    fun `a failed settle for an item that went back to pending is ignored`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        // The lease expired and a fresh attempt already returned the item to the drain's hands.
        store.recordFailure(
            "item-1",
            attempts = 1,
            nextRunAtEpochMillis = 99L,
            lastError = "earlier",
            expectedState = UploaderItemState.IN_FLIGHT,
            expectedClaim = handler.attempts.single().claim,
        )

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Failed())

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(1, item.attempts, "a stale report must not spend another attempt")
        assertEquals("earlier", item.lastError)
    }

    @Test
    fun `a failed settle for a parked item does not revive it`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")
        engine.drain()
        store.park("item-1", "permanent")

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Failed())

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PARKED, item.state, "parked is a deliberate decision")
        assertEquals("permanent", item.lastError)
    }

    @Test
    fun `a failed settle is rejected when the drain re-hands the item mid-settle`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()

        // The settle path has read the row and is about to write. In production the drain runs on
        // its own coroutine, and this is the window where it can notice an expired lease and re-hand
        // the item — leaving the report in flight about a claim that no longer exists.
        store.afterNextGetById = {
            clock.millis = 10_001L
            store.markInFlight("item-1", 25_000L, UploaderItemState.IN_FLIGHT, expectedClaim = handler.attempts.single().claim)
        }

        engine.settle("item-1", handler.attempts.single().claim, SettleResult.Failed())

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.IN_FLIGHT, item.state, "the fresh claim must survive")
        assertEquals(25_000L, item.leaseUntilEpochMillis)
        assertEquals(0, item.attempts, "the stale report must not spend the retry budget")
        assertNull(item.lastError, "nor record its error over the fresh claim")
    }

    @Test
    fun `a delivered settle mid-re-hand still wins because delivery is a fact`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val claim: Long = handler.attempts.single().claim
        store.afterNextGetById = { store.markInFlight("item-1", 25_000L, UploaderItemState.IN_FLIGHT, expectedClaim = claim) }

        engine.settle("item-1", claim, SettleResult.Delivered)

        assertNull(
            store.find("item-1"),
            "the effect landed; re-handing it would deliver it a second time",
        )
    }

    @Test
    fun `an item detached by a process that died is picked up by the next one`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val dying = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val first: UploaderEngine = testEngine(store, listOf(dying), backgroundScope, clock = clock)
        first.enqueue(dying, "p")
        first.drain()
        first.close() // the process is gone; the row and its lease are not

        val reviving = TestHandler()
        val second: UploaderEngine = testEngine(store, listOf(reviving), backgroundScope, clock = clock)

        // Before the lease expires the new process leaves it alone — the old executor may still be
        // alive in some other process or OS-managed session.
        second.drain()
        assertEquals(0, reviving.attempts.size)

        clock.millis = 10_001L
        second.drain()

        assertEquals(1, reviving.attempts.size)
        assertTrue(reviving.attempts.single().wasDetached)
        assertEquals(emptyList(), store.items, "the recovered item was delivered")
    }

    @Test
    fun `settling as failed on an item whose handler is gone parks it`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        testEngine(store, listOf(handler), backgroundScope).also {
            it.enqueue(handler, "p")
            it.drain()
        }
        val orphaned: UploaderEngine = testEngine(store, emptyList(), backgroundScope)

        orphaned.settle("item-1", handler.attempts.single().claim, SettleResult.Failed())

        assertEquals(UploaderItemState.PARKED, store.find("item-1")?.state)
    }

    // --- Which claim a report comes from ------------------------------------------------------------

    @Test
    fun `every hand-off of an item is made under a claim of its own`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        clock.millis = 10_001L
        engine.drain()

        val claims: List<Long> = handler.attempts.map { it.claim }
        assertEquals(2, claims.toSet().size, "a re-hand-off must hold a claim the first executor does not: $claims")
        assertEquals(claims.last(), store.find("item-1")?.claim, "the item is held under the latest claim")
    }

    @Test
    fun `an expired executor's failure after a re-hand neither returns the item to pending nor spends an attempt`() =
        runTest {
            val store = TestUploaderStore()
            val clock = TestClock(millis = 0L)
            val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
            engine.enqueue(handler, "p")
            engine.drain()
            val first: AttemptContext = handler.attempts.single()
            // The first executor goes silent past its lease, and the drain re-hands the item.
            clock.millis = 10_001L
            engine.drain()
            assertTrue(handler.attempts[1].wasDetached, "precondition: the item was re-handed")

            // Only now does the first executor report.
            engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("first executor broke")))

            val item: UploaderItem = assertNotNull(store.find("item-1"))
            assertEquals(UploaderItemState.IN_FLIGHT, item.state, "the second executor's claim must survive")
            assertEquals(20_001L, item.leaseUntilEpochMillis)
            assertEquals(0, item.attempts, "a superseded executor's failure must not spend the retry budget")
            assertNull(item.lastError)
        }

    @Test
    fun `an expired executor's park after a re-hand does not take the item from its new executor`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val first: AttemptContext = handler.attempts.single()
        clock.millis = 10_001L
        engine.drain()

        engine.settle("item-1", first.claim, SettleResult.Park("first executor gave up"))

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.IN_FLIGHT, item.state, "the second executor's claim must survive")
        assertEquals(20_001L, item.leaseUntilEpochMillis)
        assertNull(item.lastError)
    }

    @Test
    fun `the re-handed executor's own failure still spends an attempt`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        clock.millis = 10_001L
        engine.drain()
        val second: AttemptContext = handler.attempts[1]

        engine.settle("item-1", second.claim, SettleResult.Failed(IllegalStateException("second executor broke")))

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(UploaderItemState.PENDING, item.state, "the current claim's failure returns it to rotation")
        assertEquals(1, item.attempts)
        assertTrue(item.lastError.orEmpty().contains("second executor broke"))
    }

    @Test
    fun `an expired executor's delivery after a re-hand still settles the item`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val first: AttemptContext = handler.attempts.single()
        clock.millis = 10_001L
        engine.drain()

        engine.settle("item-1", first.claim, SettleResult.Delivered)

        assertNull(store.find("item-1"), "the effect landed, whichever executor landed it")
    }

    @Test
    fun `an expired executor's drop after a re-hand still removes the item`() = runTest {
        val store = TestUploaderStore()
        val clock = TestClock(millis = 0L)
        val handler = TestHandler(onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) })
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
        engine.enqueue(handler, "p")
        engine.drain()
        val first: AttemptContext = handler.attempts.single()
        clock.millis = 10_001L
        engine.drain()

        engine.settle("item-1", first.claim, SettleResult.Drop("the message was deleted"))

        assertNull(store.find("item-1"), "an effect no longer owed is owed by no executor")
    }

    @Test
    fun `a duplicate failure from an executor whose item was re-claimed under the same lease spends nothing`() =
        runTest {
            val store = TestUploaderStore()
            val clock = TestClock(millis = 0L)
            val handler = TestHandler(
                retryPolicy = FixedRetryPolicy(delayMillis = 0L),
                onExecute = { _, _ -> AttemptResult.Detached(leaseMillis = 10_000) },
            )
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = clock)
            engine.enqueue(handler, "p")
            engine.drain()
            val first: AttemptContext = handler.attempts.single()
            engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("503")))
            // Re-claimed in the same millisecond: the new claim carries the very lease the first did.
            engine.drain()
            assertEquals(10_000L, store.find("item-1")?.leaseUntilEpochMillis, "precondition: the same lease")

            // The first executor's report arrives a second time.
            engine.settle("item-1", first.claim, SettleResult.Failed(IllegalStateException("503")))

            val item: UploaderItem = assertNotNull(store.find("item-1"))
            assertEquals(UploaderItemState.IN_FLIGHT, item.state, "the second claim must survive")
            assertEquals(1, item.attempts, "one failure, reported twice, spends one attempt")
        }

    @Test
    fun `a failure reported before the hand-off is recorded counts against the attempt that made it`() = runTest {
        val store = TestUploaderStore()
        lateinit var engine: UploaderEngine
        val handler = TestHandler(
            onExecute = { context, _ ->
                // A fast executor reports before execute() has even returned its Detached.
                engine.settle(context.id, context.claim, SettleResult.Failed(IllegalStateException("503")))
                AttemptResult.Detached(leaseMillis = 10_000)
            },
        )
        engine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()

        val item: UploaderItem = assertNotNull(store.find("item-1"))
        assertEquals(1, item.attempts, "the attempt's own failure must count")
        assertEquals(UploaderItemState.PENDING, item.state, "and the hand-off that followed it must not claim the item")
        assertEquals(0L, item.leaseUntilEpochMillis)
    }
}
