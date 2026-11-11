package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadField
import io.github.jamal_wia.kmptoolkit.uploader.UploadHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploadPreparation
import io.github.jamal_wia.kmptoolkit.uploader.UploadRequest
import io.github.jamal_wia.kmptoolkit.uploader.UploadTransport
import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.assertEquals
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext

/**
 * Round 7 (A1): a deterministic scheduler over the REAL SQLite store. Every call the engine, an operation or a handler
 * makes to the store is a scheduling point that parks until the scheduler releases it; the store call itself then runs
 * against SQLite on its own thread and the scheduler waits for it to finish, so a schedule is a total order of store
 * calls and replays identically. All coroutines run on one virtual-time test dispatcher, so between two steps
 * nothing else runs.
 */
class ActorElement(val name: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ActorElement>
}

class Sched(val testScheduler: TestCoroutineScheduler) {
    class Parked(val actor: String, val what: String, val gate: CompletableDeferred<Unit>, val seq: Int)

    val parked: MutableList<Parked> = ArrayList()
    var open: Boolean = false
    var awaiting: Boolean = false
    var active: Int = 0
    var steps: Int = 0
    var seq: Int = 0
    var midAction: (() -> Unit)? = null

    /** Runs the action a step asked to be done in the middle of its call (a close landing while the call runs). */
    fun mid() {
        val action = midAction ?: return
        midAction = null
        action()
    }
    val log: MutableList<String> = ArrayList()

    suspend fun <T> atom(what: String, body: suspend () -> T): T {
        if (open) return body()
        val actor: String = currentCoroutineContext()[ActorElement]?.name ?: "?"
        val p = Parked(actor, what, CompletableDeferred(), seq++)
        parked += p
        try {
            p.gate.await()
        } catch (e: CancellationException) {
            parked.remove(p)
            throw e
        }
        awaiting = false
        active++
        try {
            return body()
        } finally {
            active--
        }
    }

    /** Releases [p] and drives the test dispatcher until the released call has completed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun step(p: Parked) {
        steps++
        log += "${p.actor}:${p.what}"
        parked.remove(p)
        awaiting = true
        p.gate.complete(Unit)
        settle()
        midAction = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun settle() {
        var waited = 0
        while (true) {
            testScheduler.runCurrent()
            if (!awaiting && active == 0) return
            // The store call finishes on the database thread; spin briefly before sleeping, it is usually microseconds.
            if (waited < 400) Thread.yield() else withContext(Dispatchers.Default) { delay(1) }
            waited++
            check(waited < 8_400) { "a released store call never completed (log=$log)" }
        }
    }
}

class Observed {
    val effects: MutableList<Effect> = ArrayList()
    val removedByOp: MutableMap<String, Int> = HashMap()
    val closedAt: MutableMap<String, Int> = HashMap()
    val results: MutableMap<String, String?> = HashMap()
    val rowViolations: MutableList<String> = ArrayList()
    val storeFailures: MutableList<String> = ArrayList()
    val threw: MutableSet<String> = HashSet()
    val launches: MutableList<Launch> = ArrayList()
    val cancels: MutableList<Launch> = ArrayList()
    val deliveredAtId: MutableMap<String, Int> = HashMap()

    data class Effect(val payload: String, val engineTag: String, val step: Int)
    data class Launch(val id: String, val engineTag: String, val step: Int)
}

/** The real store behind a scheduling point per call, recording which calls withdrew an item for an operation. */
class GatedStore(
    private val inner: UploaderStore,
    private val sched: Sched,
    private val observed: Observed,
    private val blindDeleteByTag: Boolean = false,
    private val failAt: Int = -1,
    private val failAfterCommit: Boolean = false,
) : UploaderStore {
    private var calls: Int = 0

    /** A scheduling point whose call number [failAt] fails, before or after the store committed (never in the final phase). */
    private suspend fun <T> gate(what: String, body: suspend () -> T): T = sched.atom(what) {
        val n: Int = if (sched.open) Int.MIN_VALUE else calls++
        if (n == failAt) throw IllegalStateException("injected failure before call $n ($what)")
        val result: T = body()
        // A store that notices a cancelled caller reports it after the write committed (UploaderStore KDoc, "Atomicity"),
        // as the real one does when it hops to its database thread; a close landing in the middle of the call makes
        // exactly that happen. Inside a NonCancellable write the caller cannot be cancelled and nothing is reported.
        sched.mid()
        if (!sched.open) currentCoroutineContext().ensureActive()
        result
    }

    private suspend fun actor(): String = currentCoroutineContext()[ActorElement]?.name ?: "?"

    override suspend fun insertKeep(record: UploaderItem): Boolean = gate("insertKeep(${record.payload})") { inner.insertKeep(record) }

    override suspend fun insertReplace(record: UploaderItem): UploaderItem? {
        val who: String = actor()
        return gate("insertReplace(${record.payload})") {
            val old: UploaderItem? = inner.insertReplace(record)
            if (old != null && who.startsWith("op:")) observed.removedByOp.putIfAbsent(old.id, sched.steps)
            old
        }
    }

    override suspend fun getAllActive(): List<UploaderItem> = gate("getAllActive") { inner.getAllActive() }
    override suspend fun getById(id: String): UploaderItem? = gate("getById($id)") { inner.getById(id) }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
        disposition: FailureDisposition,
    ): Boolean = gate("recordFailure($id,$disposition)") {
        inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
    }

    override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
        gate("markInFlight($id)") { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

    override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
        gate("park($id)") { inner.park(id, lastError, expectedState, expectedClaim) }

    override suspend fun deleteById(id: String) {
        val who: String = actor()
        gate("deleteById($id)") {
            inner.deleteById(id)
            if (who.startsWith("op:")) observed.removedByOp.putIfAbsent(id, sched.steps)
        }
    }

    override suspend fun deleteByTag(tag: String): List<UploaderItem> {
        val who: String = actor()
        return gate("deleteByTag($tag)") {
            val removed: List<UploaderItem> = inner.deleteByTag(tag)
            if (who.startsWith("op:")) removed.forEach { observed.removedByOp.putIfAbsent(it.id, sched.steps) }
            // The negative control: a store that deletes but reports nothing, the lost-cancel bug of an old round.
            if (blindDeleteByTag) emptyList() else removed
        }
    }

    override fun observeByType(type: String): Flow<List<UploaderItem>> = inner.observeByType(type)
    override suspend fun clearAll() = inner.clearAll()
}

object QuickRetry : RetryPolicy {
    override val maxDelayMillis: Long = 50L
    override val giveUp: GiveUpPolicy = GiveUpPolicy.Never
    override fun backoffMillis(attempts: Int): Long = 20L
}

/** A plain handler whose effect is itself a scheduling point; [engineTag] says which engine ran it. */
class EffectHandler(private val sched: Sched, private val observed: Observed, private val engineTag: String) :
    UploaderHandler<String> {
    override val type: String = "adv.r7.plain"
    override val retryPolicy: RetryPolicy = QuickRetry
    override fun encodePayload(payload: String): String = payload
    override fun decodePayload(raw: String): String = raw
    override suspend fun execute(context: AttemptContext, payload: String): AttemptResult =
        sched.atom("effect($payload)") {
            observed.effects += Observed.Effect(payload, engineTag, sched.steps)
            sched.mid()
            AttemptResult.Success
        }
}

/** An upload handler whose preparation is a scheduling point; the transport only records what the engine asks of it. */
class UploadEffectHandler(
    private val sched: Sched,
    transport: UploadTransport,
) : UploadHandler<String>(transport) {
    override val type: String = "adv.r7.upload"
    override val retryPolicy: RetryPolicy = QuickRetry
    override fun encodePayload(payload: String): String = payload
    override fun decodePayload(raw: String): String = raw
    override suspend fun prepareUpload(context: AttemptContext, payload: String): UploadPreparation =
        sched.atom("prepare($payload)") {
            sched.mid()
            UploadPreparation.Proceed(UploadRequest("https://example.invalid/u", fields = listOf(UploadField.Text("a", payload))))
        }
}

class RecordingTransport(private val sched: Sched, private val observed: Observed, private val engineTag: String) : UploadTransport {
    override val leaseMillis: Long = 15_000L
    override fun launch(itemId: String, request: UploadRequest) = error("re-hand-aware overload expected")
    override fun launch(itemId: String, isRehandOff: Boolean, request: UploadRequest) {
        observed.launches += Observed.Launch(itemId, engineTag, sched.steps)
    }

    override fun cancel(itemId: String) {
        observed.cancels += Observed.Launch(itemId, engineTag, sched.steps)
    }
}

class StepClock(var millis: Long) : UploaderClock {
    override fun nowEpochMillis(): Long = millis
}

enum class Op(val actor: String) { KEEP_B("op:keepB"), REPLACE_C("op:replaceC"), FREE_D("op:freeD"), CANCEL_A("op:cancelA"), CANCEL_TAG("op:cancelTag"), SETTLE_A("op:settleA") }

enum class Meta { CLOSE, RESTART, ADVANCE, TRIGGER, CLOSE_MID, KILL }

enum class Seed { EMPTY, PENDING_A, GATED_A, INFLIGHT_A, EXPIRED_A }

class Scenario(
    val name: String,
    val seed: Seed,
    val ops: List<Op>,
    val metas: List<Meta>,
    /** A replacement engine may start only after the closed one has nothing parked (quiesced), like R6-05b. */
    val strictRestart: Boolean,
    val preemptionBound: Int,
    /** A store that lies about what `deleteByTag` removed: the harness must then report violations. */
    val blindDeleteByTag: Boolean = false,
    /** The call (by number) of the store that fails, or -1; [failAfterCommit] says whether the write landed first. */
    val failAt: Int = -1,
    val failAfterCommit: Boolean = false,
    /** Items are uploads handed to a recording transport instead of plain effects. */
    val upload: Boolean = false,
)

/** A copy of [this] with another preemption bound. */
fun Scenario.withBound(bound: Int): Scenario = Scenario(
    name, seed, ops, metas, strictRestart, bound, blindDeleteByTag, failAt, failAfterCommit, upload,
)

class RunResult(val choices: List<Int>, val optionCounts: List<Int>, val violations: List<String>, val log: List<String>)

class ScenarioRunner(
    private val storage: UploaderStorage,
    private val testScheduler: TestCoroutineScheduler,
    private val parentScope: CoroutineScope,
    private val scenario: Scenario,
) {
    private val config: UploaderConfig = UploaderConfig(
        heartbeatInterval = 30.seconds,
        minAlarmDelay = 100.milliseconds,
        drainPollInterval = 10.milliseconds,
    )

    suspend fun run(prefix: List<Int>, random: kotlin.random.Random? = null): RunResult {
        storage.store.clearAll()
        val sched = Sched(testScheduler)
        val observed = Observed()
        val clock = StepClock(1_000_000L)
        val gated = GatedStore(storage.store, sched, observed, scenario.blindDeleteByTag, scenario.failAt, scenario.failAfterCommit)
        var counter = 0
        val ids: () -> String = { "g${counter++}" }
        val job = Job(parentScope.coroutineContext[Job])
        val scope = CoroutineScope(parentScope.coroutineContext + job)
        val engines: MutableList<Triple<String, UploaderEngine, UploaderHandler<String>>> = ArrayList()
        val owners: MutableMap<String, Job> = HashMap()
        var engineSeq = 0
        val rowType: String = if (scenario.upload) "adv.r7.upload" else "adv.r7.plain"

        fun newEngine(): Triple<String, UploaderEngine, UploaderHandler<String>> {
            val tag = "e${++engineSeq}"
            owners[tag] = Job(job)
            val handler: UploaderHandler<String> =
                if (scenario.upload) UploadEffectHandler(sched, RecordingTransport(sched, observed, tag)) else EffectHandler(sched, observed, tag)
            val engine: UploaderEngine = createUploaderEngine(
                store = gated,
                handlers = listOf(handler),
                scope = CoroutineScope(parentScope.coroutineContext + owners.getValue(tag)) + ActorElement(tag),
                config = config,
                clock = clock,
                idGenerator = ids,
            )
            engine.start()
            return Triple(tag, engine, handler).also { engines += it }
        }

        fun current(): Triple<String, UploaderEngine, UploaderHandler<String>> = engines.last()

        // Seed the row before any engine exists (the previous process left it behind).
        when (scenario.seed) {
            Seed.EMPTY -> Unit
            Seed.PENDING_A, Seed.GATED_A, Seed.INFLIGHT_A, Seed.EXPIRED_A -> storage.store.insertKeep(
                UploaderItem(
                    id = "A", type = rowType, payload = "A", schemaVersion = 1, uniqueKey = "k", orderingKey = null,
                    tag = "t",
                    state = if (scenario.seed == Seed.INFLIGHT_A || scenario.seed == Seed.EXPIRED_A) UploaderItemState.IN_FLIGHT else UploaderItemState.PENDING,
                    attempts = 0,
                    nextRunAtEpochMillis = if (scenario.seed == Seed.GATED_A) clock.millis + 5_000L else 0L,
                    createdAtEpochMillis = clock.millis, lastError = null,
                    leaseUntilEpochMillis = when (scenario.seed) {
                        Seed.INFLIGHT_A -> clock.millis + 10_000L
                        Seed.EXPIRED_A -> clock.millis - 1L
                        else -> 0L
                    },
                    claim = if (scenario.seed == Seed.INFLIGHT_A || scenario.seed == Seed.EXPIRED_A) 1L else 0L,
                ),
            )
        }
        newEngine()
        testScheduler.runCurrent()

        val opJobs: MutableList<Job> = ArrayList()
        val opEngine: MutableMap<String, String> = HashMap()
        val opFinished: MutableSet<String> = HashSet()
        for (op in scenario.ops) {
            opJobs += scope.launch(ActorElement(op.actor)) {
                // The first scheduling point of an operation is its start: which engine it talks to is decided there.
                val (tag: String, engine: UploaderEngine, handler: UploaderHandler<String>) = sched.atom("start") { current().also { opEngine[op.actor] = it.first } }
                try {
                    when (op) {
                        Op.KEEP_B -> observed.results["B"] = (engine.enqueue(handler, "B", "k", "t", ConflictPolicy.KEEP))
                        Op.REPLACE_C -> observed.results["C"] = engine.enqueue(handler, "C", "k", "t", ConflictPolicy.REPLACE)
                        Op.FREE_D -> observed.results["D"] = engine.enqueue(handler, "D", null, "t", ConflictPolicy.KEEP)
                        Op.CANCEL_A -> engine.cancel("A")
                        Op.CANCEL_TAG -> engine.cancelByTag("t")
                        Op.SETTLE_A -> {
                            engine.settle("A", 0L, SettleResult.Delivered)
                            observed.deliveredAtId["A"] = sched.steps
                        }
                    }
                } catch (e: CancellationException) {
                    observed.threw += op.actor
                    observed.storeFailures += "${op.name} on $tag cancelled"
                } catch (e: Throwable) {
                    observed.threw += op.actor
                    observed.storeFailures += "${op.name} on $tag threw $e"
                } finally {
                    opFinished += op.actor
                }
            }
        }
        testScheduler.runCurrent()

        val metasLeft: MutableList<Meta> = scenario.metas.toMutableList()
        val choices: MutableList<Int> = ArrayList()
        val optionCounts: MutableList<Int> = ArrayList()
        var lastActor: String? = null
        var preemptions = 0
        var closedEngine: String? = null
        val violations: MutableList<String> = ArrayList()

        while (true) {
            testScheduler.runCurrent()
            checkRows(storage, observed)
            val gatedOptions: List<Sched.Parked> = sched.parked
                .sortedWith(compareBy({ if (it.actor == lastActor) 0 else 1 }, { it.actor }, { it.seq }))
            val metaOptions: List<Meta> = metasLeft.filter { m ->
                when (m) {
                    // A call made through a closed engine is outside its contract once a replacement runs, so the
                    // replacement starts after every operation that was bound to the closed engine has returned.
                    Meta.RESTART -> closedEngine != null &&
                        (!scenario.strictRestart || sched.parked.none { it.actor == closedEngine }) &&
                        opEngine.filterValues { it == closedEngine }.keys.all { it in opFinished }
                    Meta.CLOSE_MID -> false // offered below, once per parked call
                    else -> true
                }
            }
            val lastStillParked: Boolean = lastActor != null && gatedOptions.any { it.actor == lastActor }
            val allowedGated: List<Sched.Parked> =
                if (preemptions >= scenario.preemptionBound && lastStillParked) gatedOptions.filter { it.actor == lastActor } else gatedOptions
            val midOptions: List<Sched.Parked> =
                if (Meta.CLOSE_MID in metasLeft && closedEngine == null) allowedGated.filter { it.what != "start" } else emptyList()
            val count: Int = allowedGated.size + metaOptions.size + midOptions.size
            if (count == 0) break
            check(sched.steps < 600) { "schedule longer than 600 steps - livelock? log=${sched.log}" }
            val index: Int = if (optionCounts.size < prefix.size) prefix[optionCounts.size] else random?.nextInt(count) ?: 0
            check(index < count) { "replay diverged: choice $index of $count at depth ${optionCounts.size}; log=${sched.log}" }
            choices += index
            optionCounts += count
            if (index < allowedGated.size) {
                val p: Sched.Parked = allowedGated[index]
                if (lastStillParked && p.actor != lastActor) preemptions++
                lastActor = p.actor
                sched.step(p)
            } else if (index >= allowedGated.size + metaOptions.size) {
                val p: Sched.Parked = midOptions[index - allowedGated.size - metaOptions.size]
                if (lastStillParked && p.actor != lastActor) preemptions++
                lastActor = p.actor
                metasLeft.remove(Meta.CLOSE_MID)
                val (tag: String, engine: UploaderEngine, _) = current()
                sched.midAction = {
                    engine.close()
                    closedEngine = tag
                    observed.closedAt.putIfAbsent(tag, sched.steps)
                }
                sched.log += "mid-close@${p.actor}:${p.what}"
                sched.step(p)
            } else {
                val m: Meta = metaOptions[index - allowedGated.size]
                metasLeft.remove(m)
                sched.steps++
                sched.log += "meta:$m"
                when (m) {
                    Meta.CLOSE -> {
                        val (tag, engine, _) = current()
                        engine.close()
                        closedEngine = tag
                        observed.closedAt.putIfAbsent(tag, sched.steps)
                    }
                    Meta.KILL -> {
                        val (tag, _, _) = current()
                        owners.getValue(tag).cancel()
                        closedEngine = tag
                        observed.closedAt.putIfAbsent(tag, sched.steps)
                    }
                    Meta.CLOSE_MID -> Unit
                    Meta.RESTART -> {
                        newEngine()
                        closedEngine = null
                    }
                    Meta.ADVANCE -> {
                        clock.millis += 20_000L
                        testScheduler.advanceTimeBy(20_000L)
                    }
                    Meta.TRIGGER -> current().second.trigger()
                }
                sched.settle()
            }
        }
        // Final phase: scheduling off. A closed engine is replaced (the next process start) and virtual time runs.
        sched.open = true
        sched.parked.toList().forEach { it.gate.complete(Unit) }
        sched.parked.clear()
        sched.settle()
        if (observed.closedAt.containsKey(engines.last().first)) newEngine()
        var quietRounds = 0
        var round = 0
        while (round < 400) {
            round++
            if (scenario.upload) {
                // The executor of every upload now in flight reports it delivered, before any lease can expire.
                for (row in storage.store.getAllActive().filter { it.state == UploaderItemState.IN_FLIGHT }) {
                    engines.last().second.settle(row.id, row.claim, SettleResult.Delivered)
                    observed.deliveredAtId.putIfAbsent(row.id, Int.MAX_VALUE)
                }
            }
            clock.millis += 5_000L
            testScheduler.advanceTimeBy(5_000L)
            sched.settle()
            withContext(Dispatchers.Default) { delay(2) }
            sched.settle()
            val left: List<UploaderItem> = storage.store.getAllActive()
            if (left.isEmpty()) {
                quietRounds++
                if (quietRounds >= 2) break
            } else {
                quietRounds = 0
            }
        }
        val left: List<UploaderItem> = storage.store.getAllActive()
        if (left.isNotEmpty()) violations += "queue never drained: $left"
        if (scenario.upload) judgeUpload(scenario, observed, violations) else judge(scenario, observed, violations)
        engines.forEach { it.second.close() }
        job.cancel()
        sched.settle()
        val unexpectedThrows: List<String> = if (scenario.failAt >= 0) emptyList() else observed.storeFailures.filter { it.contains("threw") }
        return RunResult(choices, optionCounts, violations + observed.rowViolations + unexpectedThrows, sched.log)
    }

    private suspend fun checkRows(storage: UploaderStorage, observed: Observed) {
        val rows: List<UploaderItem> = storage.store.getAllActive()
        val byKey: Map<String?, List<UploaderItem>> = rows.filter { it.uniqueKey != null }.groupBy { it.uniqueKey }
        byKey.forEach { (key, list) ->
            if (list.size > 1 && observed.rowViolations.size < 3) observed.rowViolations += "two live rows hold unique key $key: $list"
        }
    }

    private fun judgeUpload(scenario: Scenario, observed: Observed, violations: MutableList<String>) {
        val idOf: Map<String, String?> = mapOf("A" to (if (scenario.seed == Seed.EMPTY) null else "A")) + observed.results
        val timeMayPass: Boolean = Meta.ADVANCE in scenario.metas || scenario.seed == Seed.EXPIRED_A
        for (payload in listOf("A", "B", "C", "D")) {
            val opActor: String? = mapOf("B" to "op:keepB", "C" to "op:replaceC", "D" to "op:freeD")[payload]
            if (opActor != null && opActor in observed.threw) continue // its outcome is unknown after an injected failure
            val id: String = idOf[payload] ?: continue
            val launches: List<Observed.Launch> = observed.launches.filter { it.id == id }
            val removedAt: Int? = observed.removedByOp[id]
            // Int.MAX_VALUE marks a delivery made by the executor after the schedule ended: nothing is launched after it.
            val deliveredAt: Int? = observed.deliveredAtId[id]
            val goneAt: Int = listOfNotNull(removedAt, deliveredAt).minOrNull() ?: Int.MAX_VALUE
            val late: List<Observed.Launch> = launches.filter { it.step > goneAt }
            if (late.isNotEmpty()) violations += "upload of $payload (id $id) was launched after its row was removed or delivered at step $goneAt: $late"
            if (removedAt != null && deliveredAt == null && launches.isNotEmpty() && observed.cancels.none { it.id == id && it.step > launches.maxOf { l -> l.step } }) {
                violations += "withdrawn upload of $payload (id $id) was launched at ${launches.map { it.step }} but never cancelled after its last launch (cancels=${observed.cancels.filter { it.id == id }})"
            }
            if (!timeMayPass && launches.size > 1) violations += "upload of $payload (id $id) was launched ${launches.size} times with no lease expiry: $launches"
            val startsPending: Boolean = payload != "A" || scenario.seed == Seed.PENDING_A || scenario.seed == Seed.GATED_A
            if (removedAt == null && startsPending && launches.isEmpty() && deliveredAt == null) {
                violations += "upload of $payload (id $id) was enqueued, never withdrawn and never launched"
            }
        }
        observed.launches.forEach { l ->
            val closed: Int? = observed.closedAt[l.engineTag]
            if (closed != null && l.step > closed) violations += "engine ${l.engineTag} launched ${l.id} at step ${l.step} after its close at step $closed"
        }
    }

    private fun judge(scenario: Scenario, observed: Observed, violations: MutableList<String>) {
        val effectsBy: Map<String, List<Observed.Effect>> = observed.effects.groupBy { it.payload }
        // id of each payload: A is "A"; others from the enqueue result
        val idOf: Map<String, String?> = mapOf("A" to (if (scenario.seed == Seed.EMPTY) null else "A")) + observed.results
        for (payload in listOf("A", "B", "C", "D")) {
            val opActor: String? = mapOf("B" to "op:keepB", "C" to "op:replaceC", "D" to "op:freeD")[payload]
            if (opActor != null && opActor in observed.threw) continue // its outcome is unknown after an injected failure
            val id: String? = idOf[payload]
            val effects: List<Observed.Effect> = effectsBy[payload].orEmpty()
            if (id == null) {
                if (effects.isNotEmpty()) violations += "payload $payload was never enqueued (or its KEEP was refused) but ran: $effects"
                continue
            }
            val removedAt: Int? = observed.removedByOp[id]
            if (removedAt != null) {
                val late: List<Observed.Effect> = effects.filter { it.step > removedAt }
                if (late.isNotEmpty()) violations += "payload $payload (id $id) ran after its withdrawal completed at step $removedAt: $late"
                if (scenario.strictRestart && scenario.failAt < 0 && effects.size > 1) violations += "withdrawn payload $payload ran ${effects.size} times: $effects"
            } else {
                if (effects.isEmpty()) violations += "payload $payload (id $id) was enqueued, never withdrawn and never ran"
                if (scenario.strictRestart && scenario.failAt < 0 && effects.size > 1) violations += "payload $payload (id $id) delivered ${effects.size} times: $effects"
            }
        }
        observed.effects.forEach { e ->
            val closed: Int? = observed.closedAt[e.engineTag]
            if (closed != null && e.step > closed) violations += "engine ${e.engineTag} ran effect ${e.payload} at step ${e.step} after its close at step $closed"
        }
    }
}

/** The real SQLite storages of a test class and the exhaustive explorer over [Scenario]s. */
abstract class InterleavingTestBase {

    protected val files: MutableList<String> = mutableListOf()
    protected val storages: MutableList<UploaderStorage> = mutableListOf()

    @kotlin.test.AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    protected fun open(): UploaderStorage {
        val name = "adv.a1r7.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    /**
     * The suite runs every scenario with no preemption of an actor that still has a call to make (a few hundred
     * orderings each). The deep run, with the marker file `kmptoolkit-r7-deep` in the system temp directory, uses the
     * bound each scenario was written with (up to about twelve thousand orderings each, minutes per scenario).
     */
    private val deep: Boolean = java.io.File(System.getProperty("java.io.tmpdir"), "kmptoolkit-r7-deep").exists()

    protected fun explore(declared: Scenario, cap: Int, inMemory: Boolean = false) = runBlocking { withTimeout(280.seconds) {
        val scenario: Scenario = if (deep) declared else declared.withBound(0)
        val storage: UploaderStorage = if (inMemory) InMemoryStorage() else open()
        // A scheduler of its own and runBlocking, not runTest: runTest skips virtual time whenever the body waits.
        val testScheduler = TestCoroutineScheduler()
        val runner = ScenarioRunner(storage, testScheduler, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), scenario)
        val pending: ArrayDeque<List<Int>> = ArrayDeque()
        pending.addLast(emptyList())
        var explored = 0
        val failures: MutableList<String> = mutableListOf()
        val started: Long = System.nanoTime()
        while (pending.isNotEmpty() && explored < cap && System.nanoTime() - started < 150_000_000_000L) {
            val prefix: List<Int> = pending.removeLast()
            val result: RunResult = runner.run(prefix)
            explored++
            for (depth in prefix.size until result.optionCounts.size) {
                for (alt in 1 until result.optionCounts[depth]) pending.addLast(result.choices.take(depth) + alt)
            }
            if (result.violations.isNotEmpty() && failures.size < 3) {
                failures += "${scenario.name}: schedule ${result.choices} -> ${result.violations}\n  log=${result.log}"
            }
        }
        println("R7 explored ${scenario.name}: $explored schedules, ${pending.size} unexplored")
        assertEquals(emptyList(), failures, "${scenario.name}: violations in $explored schedules")
        if (!deep) assertEquals(0, pending.size, "${scenario.name}: the bound was too large or too slow, ${pending.size} schedules left after $explored")
    } }

}

/** The in-memory store of the testing module behind the storage interface, for differential runs against SQLite. */
class InMemoryStorage : UploaderStorage {
    override val store: io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore = io.github.jamal_wia.kmptoolkit.uploader.testing.InMemoryUploaderStore()
    override val transactionRunner: io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner = io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner.Direct
    override fun close() = Unit
}
