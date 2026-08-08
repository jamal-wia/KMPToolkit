package io.github.jamal_wia.kmptoolkit.outbox

import io.github.jamal_wia.kmptoolkit.outbox.spi.ConstraintProvider
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.outbox.spi.WakeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Fixtures for the engine tests.
 *
 * [TestOutboxStore] is a second, independent [OutboxStore] implementation — `InMemoryOutboxStore`
 * in `kmptoolkit-outbox-testing` is the first, and this module cannot depend on it without a
 * project cycle. That is a feature rather than a workaround: two implementations written against
 * the same written contract cross-check it, where one implementation confirming itself would not.
 */
internal class TestOutboxStore(initial: List<OutboxItem> = emptyList()) : OutboxStore {

    private val state: MutableStateFlow<List<OutboxItem>> = MutableStateFlow(initial)

    /** Set to throw once from the next [getAllActive], to simulate a transient storage failure. */
    var failNextGetAllActive: Throwable? = null

    /** Every id ever passed to [deleteById], including repeats — the duplicate-settle assertion. */
    val deletedIds: MutableList<String> = mutableListOf()

    val items: List<OutboxItem> get() = state.value

    fun find(id: String): OutboxItem? = state.value.firstOrNull { it.id == id }

    override suspend fun insertKeep(record: OutboxItem): Boolean {
        val existing: OutboxItem? = conflict(record)
        return when (existing?.state) {
            OutboxItemState.PENDING, OutboxItemState.IN_FLIGHT -> false
            OutboxItemState.PARKED -> {
                state.value = state.value.filterNot { it.id == existing.id } + record
                true
            }

            null -> {
                state.value = state.value + record
                true
            }
        }
    }

    override suspend fun insertReplace(record: OutboxItem) {
        val existing: OutboxItem? = conflict(record)
        state.value = state.value.filterNot { it.id == existing?.id } + record
    }

    override suspend fun getAllActive(): List<OutboxItem> {
        failNextGetAllActive?.let { failure ->
            failNextGetAllActive = null
            throw failure
        }
        return state.value.filter {
            it.state == OutboxItemState.PENDING || it.state == OutboxItemState.IN_FLIGHT
        }
    }

    /**
     * Runs after a [getById] has read its row, once, and is then cleared.
     *
     * It exists to model the one interleaving that cannot be produced by ordering test statements:
     * something else mutating the row between the settle path's read and its write.
     */
    var afterNextGetById: (suspend () -> Unit)? = null

    override suspend fun getById(id: String): OutboxItem? {
        val found: OutboxItem? = find(id)
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
    ): Boolean {
        val current: OutboxItem = find(id) ?: return false
        if (expectedLeaseUntilEpochMillis != null &&
            current.leaseUntilEpochMillis != expectedLeaseUntilEpochMillis
        ) {
            return false
        }
        put(
            current.copy(
                state = OutboxItemState.PENDING,
                attempts = attempts,
                nextRunAtEpochMillis = nextRunAtEpochMillis,
                lastError = lastError,
                leaseUntilEpochMillis = 0L,
            ),
        )
        return true
    }

    override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long) {
        val current: OutboxItem = find(id) ?: return
        put(
            current.copy(
                state = OutboxItemState.IN_FLIGHT,
                leaseUntilEpochMillis = leaseUntilEpochMillis,
            ),
        )
    }

    override suspend fun park(id: String, lastError: String?) {
        val current: OutboxItem = find(id) ?: return
        put(
            current.copy(
                state = OutboxItemState.PARKED,
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
        state.value = state.value.filterNot { it.tag == tag }
    }

    override fun observeByType(type: String): Flow<List<OutboxItem>> =
        state.map { items -> items.filter { it.type == type } }

    override suspend fun clearAll() {
        state.value = emptyList()
    }

    private fun conflict(record: OutboxItem): OutboxItem? {
        val key: String = record.uniqueKey ?: return null
        return state.value.firstOrNull { it.type == record.type && it.uniqueKey == key }
    }

    private fun put(updated: OutboxItem) {
        state.value = state.value.map { if (it.id == updated.id) updated else it }
    }
}

/** A hand-driven wall clock, separate from `runTest`'s virtual coroutine clock. */
internal class TestClock(var millis: Long = 0L) : OutboxClock {
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
) : OutboxHandler<String> {

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
 * same reason most tests here never call [OutboxEngine.start] at all — they call
 * [OutboxEngine.drain] directly, which is deterministic and is exactly what that method documents
 * itself as being for.
 */
internal fun testEngine(
    store: OutboxStore,
    handlers: List<OutboxHandler<*>>,
    scope: CoroutineScope,
    clock: OutboxClock = TestClock(),
    constraintProviders: List<ConstraintProvider> = emptyList(),
    transactionRunner: TransactionRunner = TransactionRunner.Direct,
    wakeScheduler: WakeScheduler = WakeScheduler.NoOp,
    config: OutboxConfig = OutboxConfig(),
    idGenerator: () -> String = SequentialIds()::next,
): OutboxEngine = createOutboxEngine(
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
