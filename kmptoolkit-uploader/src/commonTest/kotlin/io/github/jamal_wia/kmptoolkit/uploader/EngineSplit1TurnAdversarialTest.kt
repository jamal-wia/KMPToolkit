package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The test the A1 engine split (step 1) adds to the boss round 4 suspect bv4-S2 for rule P9: the orphan park is isolated like an attempt. */
class EngineSplit1TurnAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Throws from every park of [poisonedId]. */
    private class ParkThrowsStore(private val inner: TestUploaderStore, private val poisonedId: String) : UploaderStore by inner {
        var parks: Int = 0
        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean {
            if (id == poisonedId) {
                parks++
                throw IllegalStateException("database is locked")
            }
            return inner.park(id, lastError, expectedState, expectedClaim)
        }
    }

    private fun row(id: String, type: String): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = "p",
        schemaVersion = 1,
        uniqueKey = null,
        orderingKey = null,
        tag = null,
        state = UploaderItemState.PENDING,
        attempts = 0,
        nextRunAtEpochMillis = 0L,
        createdAtEpochMillis = 1L,
        lastError = null,
        claim = 1L,
    )

    @Test
    fun `P9 an orphan park that throws does not stop the items behind it in that drain`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore(listOf(row("split1e-1", "split1.gone"), row("split1e-2", "split1.here")))
            val store = ParkThrowsStore(inner, poisonedId = "split1e-1")
            val handler = TestHandler(type = "split1.here")
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), TestClock(2_000_000L))

            engine.drain()

            assertEquals(1, handler.payloads.size, "the item behind the failing orphan park ran in the same drain")
            assertEquals(1, store.parks, "the orphan park used its one turn")
        } finally {
            parent.cancel()
        }
    }
}
