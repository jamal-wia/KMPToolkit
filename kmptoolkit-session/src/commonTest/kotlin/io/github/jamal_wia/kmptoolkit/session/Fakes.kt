package io.github.jamal_wia.kmptoolkit.session

import io.github.jamal_wia.kmptoolkit.coroutines.AppDispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local doubles for this module's own tests. They deliberately do not come from
 * `:kmptoolkit-session-testing` — that module depends on this one, so the reverse edge would be a
 * project cycle. The published fixtures are covered by that module's own tests.
 */
internal class FakeCleaner(
    override val name: String = "fake",
    private val onClean: suspend () -> Unit = {},
) : SessionCleaner {

    var cleanCalls: Int = 0
        private set

    override suspend fun clean() {
        cleanCalls++
        onClean()
    }
}

/**
 * A cleaner that parks mid-teardown so a test can observe the manager while a teardown is genuinely
 * in flight.
 *
 * [awaitTeardownInFlight] is what makes such a test honest: `runTest`'s own scope dispatches with a
 * `StandardTestDispatcher`, so a `launch { endSession() }` has not started running when `launch`
 * returns. Asserting on the manager at that point — or cancelling the job — tests a teardown that
 * never began, and passes for the wrong reason.
 */
internal class Gate(name: String = "gated", private val thenThrow: (() -> Unit)? = null) {
    private val started = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()

    /** `true` once the cleaner ran past the gate to the end of its body. */
    var finished: Boolean = false
        private set

    private val gatedCleaner = FakeCleaner(name) {
        started.complete(Unit)
        released.await()
        finished = true
        thenThrow?.invoke()
    }

    fun cleaner(): SessionCleaner = gatedCleaner

    val cleanCalls: Int
        get() = gatedCleaner.cleanCalls

    /** Suspends until the cleaner has actually been entered. */
    suspend fun awaitTeardownInFlight(): Unit = started.await()

    /** Lets the parked cleaner finish. */
    fun release() {
        released.complete(Unit)
    }
}

/** Records the order in which teardown steps ran, across cleaners and the revoker. */
internal class StepRecorder {
    private val mutex = Mutex()
    private val recorded: MutableList<String> = mutableListOf()

    suspend fun record(step: String) {
        mutex.withLock { recorded += step }
    }

    suspend fun steps(): List<String> = mutex.withLock { recorded.toList() }
}

/**
 * A cleaner whose call count survives genuine multi-threaded contention — [FakeCleaner]'s plain
 * counter can lose an increment, which would make a parallelism test pass for the wrong reason.
 */
internal class ThreadSafeCountingCleaner(override val name: String = "counting") : SessionCleaner {
    private val mutex = Mutex()
    private var count: Int = 0

    override suspend fun clean() {
        mutex.withLock { count++ }
    }

    suspend fun cleanCalls(): Int = mutex.withLock { count }
}

/**
 * Real multi-threaded dispatchers for the parallelism tests.
 *
 * `DefaultAppDispatchers` is unusable here: it initializes `main` from `Dispatchers.Main`, which
 * has no implementation in a plain JVM or Native test process.
 */
internal class ParallelAppDispatchers : AppDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Default
    override val default: CoroutineDispatcher = Dispatchers.Default
}
