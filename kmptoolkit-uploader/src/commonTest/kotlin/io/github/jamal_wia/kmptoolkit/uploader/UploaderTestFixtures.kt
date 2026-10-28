package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.spi.WakeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Fixtures for the engine tests.
 *
 * [TestUploaderStore] is a second, independent [UploaderStore] implementation — `InMemoryUploaderStore`
 * in `kmptoolkit-uploader-testing` is the first, and this module cannot depend on it without a
 * project cycle. That is a feature rather than a workaround: two implementations written against
 * the same written contract cross-check it, where one implementation confirming itself would not.
 */
internal class TestUploaderStore(initial: List<UploaderItem> = emptyList()) : UploaderStore {

    private val state: MutableStateFlow<List<UploaderItem>> = MutableStateFlow(initial)

    /** Set to throw once from the next [getAllActive], to simulate a transient storage failure. */
    var failNextGetAllActive: Throwable? = null

    /** Every id ever passed to [deleteById], including repeats — the duplicate-settle assertion. */
    val deletedIds: MutableList<String> = mutableListOf()

    val items: List<UploaderItem> get() = state.value

    fun find(id: String): UploaderItem? = state.value.firstOrNull { it.id == id }

    override suspend fun insertKeep(record: UploaderItem): Boolean {
        val existing: UploaderItem? = conflict(record)
        return when (existing?.state) {
            UploaderItemState.PENDING, UploaderItemState.IN_FLIGHT -> false
            UploaderItemState.PARKED -> {
                state.value = state.value.filterNot { it.id == existing.id } + record
                true
            }

            null -> {
                state.value = state.value + record
                true
            }
        }
    }

    override suspend fun insertReplace(record: UploaderItem) {
        runOnce(beforeNextInsertReplace) { beforeNextInsertReplace = null }
        val existing: UploaderItem? = conflict(record)
        state.value = state.value.filterNot { it.id == existing?.id } + record
    }

    /**
     * Runs once after the next [getAllActive] has taken its snapshot, before the caller sees it — for
     * a write that lands between a read of the queue and whatever the caller does with that read.
     */
    var afterNextGetAllActive: (suspend () -> Unit)? = null

    /** Runs once right before the next [markInFlight] writes — a settle landing ahead of a claim. */
    var beforeNextMarkInFlight: (suspend () -> Unit)? = null

    /** Runs once right after the next [markInFlight] has claimed its row — a withdrawal ahead of a launch. */
    var afterNextMarkInFlight: (suspend () -> Unit)? = null

    /** Runs once right after the next [recordFailure] has written — a drain pass landing behind it. */
    var afterNextRecordFailure: (suspend () -> Unit)? = null

    /** Runs once right before the next [deleteByTag] deletes — an enqueue racing a tag wipe. */
    var beforeNextDeleteByTag: (suspend () -> Unit)? = null

    /** Runs once right before the next [insertReplace] writes — an enqueue racing a replacement. */
    var beforeNextInsertReplace: (suspend () -> Unit)? = null

    override suspend fun getAllActive(): List<UploaderItem> {
        failNextGetAllActive?.let { failure ->
            failNextGetAllActive = null
            throw failure
        }
        val active: List<UploaderItem> = state.value.filter {
            it.state == UploaderItemState.PENDING || it.state == UploaderItemState.IN_FLIGHT
        }
        runOnce(afterNextGetAllActive) { afterNextGetAllActive = null }
        return active
    }

    /**
     * Runs after a [getById] has read its row, once, and is then cleared.
     *
     * It exists to model the one interleaving that cannot be produced by ordering test statements:
     * something else mutating the row between the settle path's read and its write.
     */
    var afterNextGetById: (suspend () -> Unit)? = null

    /**
     * Runs before the next [getById] reads its row, once, and is then cleared — for something that
     * changes the row right before a read the test cannot otherwise reach, such as the hand-off's check
     * after it launched.
     */
    var beforeNextGetById: (suspend () -> Unit)? = null

    override suspend fun getById(id: String): UploaderItem? {
        beforeNextGetById?.let { hook ->
            beforeNextGetById = null
            hook()
        }
        val found: UploaderItem? = find(id)
        afterNextGetById?.let { hook ->
            afterNextGetById = null
            hook()
        }
        return found
    }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long?,
        disposition: FailureDisposition,
    ): Boolean {
        val current: UploaderItem = find(id) ?: return false
        if (expectedLeaseUntilEpochMillis != null &&
            current.leaseUntilEpochMillis != expectedLeaseUntilEpochMillis
        ) {
            return false
        }
        when (disposition) {
            FailureDisposition.DROP -> deleteById(id)
            FailureDisposition.RETRY, FailureDisposition.PARK -> put(
                current.copy(
                    state = if (disposition == FailureDisposition.PARK) {
                        UploaderItemState.PARKED
                    } else {
                        UploaderItemState.PENDING
                    },
                    attempts = attempts,
                    nextRunAtEpochMillis = nextRunAtEpochMillis,
                    lastError = lastError,
                    leaseUntilEpochMillis = 0L,
                ),
            )
        }
        runOnce(afterNextRecordFailure) { afterNextRecordFailure = null }
        return true
    }

    override suspend fun markInFlight(
        id: String,
        leaseUntilEpochMillis: Long,
        expectedState: UploaderItemState,
        expectedLeaseUntilEpochMillis: Long,
    ): Boolean {
        runOnce(beforeNextMarkInFlight) { beforeNextMarkInFlight = null }
        val current: UploaderItem = find(id) ?: return false
        if (current.state != expectedState || current.leaseUntilEpochMillis != expectedLeaseUntilEpochMillis) {
            return false
        }
        put(
            current.copy(
                state = UploaderItemState.IN_FLIGHT,
                leaseUntilEpochMillis = leaseUntilEpochMillis,
            ),
        )
        runOnce(afterNextMarkInFlight) { afterNextMarkInFlight = null }
        return true
    }

    override suspend fun park(id: String, lastError: String?) {
        val current: UploaderItem = find(id) ?: return
        put(
            current.copy(
                state = UploaderItemState.PARKED,
                lastError = lastError,
                leaseUntilEpochMillis = 0L,
            ),
        )
    }

    override suspend fun deleteById(id: String) {
        deletedIds += id
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun deleteByTag(tag: String) {
        runOnce(beforeNextDeleteByTag) { beforeNextDeleteByTag = null }
        state.value = state.value.filterNot { it.tag == tag }
    }

    override fun observeByType(type: String): Flow<List<UploaderItem>> =
        state.map { items -> items.filter { it.type == type } }

    override suspend fun clearAll() {
        state.value = emptyList()
    }

    /** Clears a one-shot hook before running it, so a hook that re-enters the store cannot recurse. */
    private suspend fun runOnce(hook: (suspend () -> Unit)?, clear: () -> Unit) {
        if (hook == null) return
        clear()
        hook()
    }

    private fun conflict(record: UploaderItem): UploaderItem? {
        val key: String = record.uniqueKey ?: return null
        return state.value.firstOrNull { it.type == record.type && it.uniqueKey == key }
    }

    private fun put(updated: UploaderItem) {
        state.value = state.value.map { if (it.id == updated.id) updated else it }
    }
}

/** A hand-driven wall clock, separate from `runTest`'s virtual coroutine clock. */
internal class TestClock(var millis: Long = 0L) : UploaderClock {
    override fun nowEpochMillis(): Long = millis
}

/** Counts wake scheduling so a test can assert "the app would be woken to finish this". */
internal class TestWakeScheduler : WakeScheduler {
    var scheduleCount: Int = 0
    var cancelCount: Int = 0
    var armed: Boolean = false

    override fun scheduleWake() {
        scheduleCount++
        armed = true
    }

    override fun cancelWake() {
        cancelCount++
        armed = false
    }
}

/** A constraint a test flips by hand. */
internal class TestConstraint(
    override val key: String,
    satisfied: Boolean = true,
) : ConstraintProvider {
    private val flow: MutableStateFlow<Boolean> = MutableStateFlow(satisfied)
    override val satisfied: StateFlow<Boolean> = flow
    fun set(value: Boolean) {
        flow.value = value
    }
}

/** Counts how many blocks it ran, so a test can prove the enqueue went through a transaction. */
internal class CountingTransactionRunner : TransactionRunner {
    var transactions: Int = 0
    override suspend fun <R> inTransaction(block: suspend () -> R): R {
        transactions++
        return block()
    }
}

/** A retry policy with no jitter, so an expected backoff gate is an exact number. */
internal class FixedRetryPolicy(
    private val delayMillis: Long = FIXED_DELAY_MILLIS,
    override val giveUp: GiveUpPolicy = GiveUpPolicy.Never,
) : RetryPolicy {
    override val maxDelayMillis: Long = delayMillis
    override fun backoffMillis(attempts: Int): Long = delayMillis

    companion object {
        const val FIXED_DELAY_MILLIS: Long = 1_000L
    }
}

/**
 * A handler over `String` payloads whose every interesting behavior is a constructor parameter.
 *
 * The payload is stored verbatim, so a test can assert on what was persisted without decoding, and
 * [decode] can be overridden to throw and exercise the undecodable-payload path.
 */
internal class TestHandler(
    override val type: String = DEFAULT_TYPE,
    override val schemaVersion: Int = 1,
    override val retryPolicy: RetryPolicy = FixedRetryPolicy(),
    override val constraintKeys: Set<String> = emptySet(),
    private val ordering: (String) -> String? = { null },
    private val decode: (String) -> String = { it },
    private val onExecute: suspend (AttemptContext, String) -> AttemptResult = { _, _ ->
        AttemptResult.Success
    },
) : UploaderHandler<String> {

    /** Every attempt this handler was asked to make, in order. */
    val attempts: MutableList<AttemptContext> = mutableListOf()

    /** The payload of every attempt, in order. */
    val payloads: MutableList<String> = mutableListOf()

    override fun encodePayload(payload: String): String = payload

    override fun decodePayload(raw: String): String = decode(raw)

    override fun orderingKey(payload: String): String? = ordering(payload)

    override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
        attempts += context
        payloads += payload
        return onExecute(context, payload)
    }

    companion object {
        const val DEFAULT_TYPE: String = "test.type"
    }
}

/**
 * Builds an engine for a test.
 *
 * Pass `backgroundScope` from `runTest`: the engine's heartbeat loops forever once started, and a
 * forever-looping coroutine in the test's own scope makes `runTest` hang rather than fail. For the
 * same reason most tests here never call [UploaderEngine.start] at all — they call
 * [UploaderEngine.drain] directly, which is deterministic and is exactly what that method documents
 * itself as being for.
 */
internal fun testEngine(
    store: UploaderStore,
    handlers: List<UploaderHandler<*>>,
    scope: CoroutineScope,
    clock: UploaderClock = TestClock(),
    constraintProviders: List<ConstraintProvider> = emptyList(),
    transactionRunner: TransactionRunner = TransactionRunner.Direct,
    wakeScheduler: WakeScheduler = WakeScheduler.NoOp,
    config: UploaderConfig = UploaderConfig(),
    idGenerator: () -> String = SequentialIds()::next,
): UploaderEngine = createUploaderEngine(
    store = store,
    handlers = handlers,
    scope = scope,
    constraintProviders = constraintProviders,
    transactionRunner = transactionRunner,
    wakeScheduler = wakeScheduler,
    config = config,
    clock = clock,
    idGenerator = idGenerator,
)

/** Predictable ids, so a test can name the item it enqueued. */
internal class SequentialIds(private val prefix: String = "item") {
    private var counter: Int = 0
    fun next(): String = "$prefix-${++counter}"
}

/** A handler that always fails, for give-up and backoff tests. */
internal fun failingHandler(
    type: String = TestHandler.DEFAULT_TYPE,
    retryPolicy: RetryPolicy = FixedRetryPolicy(),
    cause: Throwable? = null,
): TestHandler = TestHandler(
    type = type,
    retryPolicy = retryPolicy,
    onExecute = { _, _ -> AttemptResult.Retry(cause) },
)
