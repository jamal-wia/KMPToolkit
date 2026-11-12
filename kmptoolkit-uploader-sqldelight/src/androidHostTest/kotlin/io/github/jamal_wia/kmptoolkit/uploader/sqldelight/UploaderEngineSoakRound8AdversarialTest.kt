package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.SettleResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderClock
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 (A1, long-run soak): a seeded random workload (enqueue with every conflict policy, cancel, withdraw by tag,
 * restart of the engine, restart of the whole storage from its file, settle of detached deliveries with live and stale
 * claims, wall clock forwards by hours and days and sometimes back) over the real SQLite file, with the invariants
 * checked every few steps and a leak check at the end (rows, claims, coroutines, threads, files, descriptors).
 *
 * The default run is bounded (3 seeds of 250 steps, a few seconds). A long run is switched on by the environment variable
 * `KMPTOOLKIT_R8_SOAK_SECONDS` (seeds are drawn until the time is spent) and `KMPTOOLKIT_R8_SOAK_SEED` (first seed).
 * Every failure message carries the seed and the step.
 */
@RunWith(AndroidJUnit4::class)
class UploaderEngineSoakRound8AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        storages.forEach { runCatching { it.close() } }
        names.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun dbThreads(): Int = Thread.getAllStackTraces().keys.count { it.name == "kmptoolkit-uploader-db" && it.isAlive }

    private fun openFds(): Int = File("/dev/fd").list()?.size ?: -1

    /** What the handlers did, as the model of the world sees it. */
    private class World(val seed: Int) {
        val clockOffset: AtomicLong = AtomicLong(0L)
        val clock: UploaderClock = UploaderClock { System.currentTimeMillis() + clockOffset.get() }
        val ran: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        val succeeded: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val dropped: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val parked: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val detachedClaims: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        val delivered: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val enqueued: MutableMap<String, String?> = linkedMapOf() // id -> tag
        val withdrawn: MutableSet<String> = mutableSetOf()
        val lastClaim: MutableMap<String, Long> = mutableMapOf()
        val replaced: MutableSet<String> = mutableSetOf()
        val trace: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val idByKey: MutableMap<String, String> = mutableMapOf()

        fun snapshot(set: MutableSet<String>): Set<String> = synchronized(set) { set.toSet() }
        fun modeOf(id: String): Int = Math.floorMod(("$seed/$id").hashCode(), 10)
    }

    private val fastRetry: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 1, maxDelayMillis = 40, jitterRatio = 0.0)

    private inner class PlainHandler(val world: World) : UploaderHandler<String> {
        override val type: String = "soak.plain"
        override val retryPolicy: RetryPolicy = fastRetry
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            val n: Int = world.ran.merge(context.id, 1, Int::plus)!!
            val mode: Int = world.modeOf(context.id)
            return when {
                mode in 6..7 && n <= 1 + mode % 3 -> AttemptResult.Retry(IllegalStateException("flaky $n"))
                mode == 8 -> AttemptResult.Park("poison").also { world.parked += context.id }
                mode == 9 -> AttemptResult.Drop("obsolete").also { world.dropped += context.id }
                else -> AttemptResult.Success.also { world.succeeded += context.id }
            }
        }
    }

    private inner class DetachedHandler(val world: World) : UploaderHandler<String> {
        override val type: String = "soak.detached"
        override val retryPolicy: RetryPolicy = fastRetry
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult {
            world.ran.merge(context.id, 1, Int::plus)
            world.detachedClaims[context.id] = context.claim
            return AttemptResult.Detached(leaseMillis = 60_000L + Math.floorMod(context.id.hashCode(), 7) * 3_600_000L)
        }
    }

    private fun newEngine(world: World, storage: UploaderStorage, scope: CoroutineScope): UploaderEngine =
        createUploaderEngine(
            store = storage.store,
            handlers = listOf(PlainHandler(world), DetachedHandler(world)),
            scope = scope,
            transactionRunner = storage.transactionRunner,
            config = UploaderConfig(heartbeatInterval = 300.milliseconds, drainPollInterval = 20.milliseconds),
            clock = world.clock,
        ).also { it.start() }

    private suspend fun checkInvariants(world: World, storage: UploaderStorage, where: String) {
        val store = storage.store
        val active: List<UploaderItem> = store.getAllActive()
        val ids: List<String> = active.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "[$where] duplicate ids in the active queue")
        for (row in active) {
            assertTrue(row.id in world.enqueued, "[$where] a row nobody enqueued: ${row.id}")
            assertTrue(row.id !in world.withdrawn, "[$where] a withdrawn row came back: ${row.id}")
            assertTrue(row.attempts >= 0, "[$where] negative attempts on ${row.id}")
            assertTrue(row.state == UploaderItemState.PENDING || row.state == UploaderItemState.IN_FLIGHT, "[$where] ${row.id} state ${row.state}")
            if (row.state == UploaderItemState.PENDING) assertEquals(0L, row.leaseUntilEpochMillis, "[$where] pending row ${row.id} holds a lease")
            val before: Long? = world.lastClaim[row.id]
            if (before != null) assertTrue(row.claim >= before, "[$where] claim of ${row.id} went back from $before to ${row.claim}")
            world.lastClaim[row.id] = row.claim
        }
        // a row the model still owes must exist in some state
        for (id in world.enqueued.keys.toList()) {
            if (id in world.withdrawn || id in world.replaced) continue
            val row: UploaderItem? = store.getById(id)
            if (row == null) {
                // read AFTER the store: a handler records its verdict before the engine deletes the row
                val terminal: Set<String> = world.snapshot(world.succeeded) + world.snapshot(world.dropped) + world.snapshot(world.delivered)
                assertTrue(id in terminal, "[$where] an owed item vanished: $id; ran=${world.ran[id]} tag=${world.enqueued[id]} mode=${world.modeOf(id)} trace=${synchronized(world.trace) { world.trace.takeLast(40) }}")
            }
        }
    }

    private suspend fun soak(seed: Int, steps: Int) {
        val r = Random(seed)
        val name: String = "adv.a1r8.soak.$seed.${Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        val world = World(seed)
        val fdsBefore: Int = openFds()
        val threadsBefore: Int = dbThreads()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var storage: UploaderStorage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
        var engine: UploaderEngine = newEngine(world, storage, scope)
        val keys: List<String> = (0 until 12).map { "k$it" }
        var seq = 0
        try {
            for (step in 0 until steps) {
                val where = "seed=$seed step=$step"
                world.trace += "#$step"
                when (r.nextInt(100)) {
                    in 0..34 -> {
                        val key: String? = if (r.nextInt(3) == 0) null else keys[r.nextInt(keys.size)]
                        val tag: String? = if (r.nextInt(4) == 0) "tag${r.nextInt(3)}" else null
                        val policy: ConflictPolicy = if (r.nextBoolean()) ConflictPolicy.KEEP else ConflictPolicy.REPLACE
                        val handler: UploaderHandler<String> = if (r.nextInt(5) == 0) DetachedHandler(world) else PlainHandler(world)
                        val id: String? = engine.enqueue(handler, "payload-${seq++}", uniqueKey = key?.let { "${handler.type}/$it" }, tag = tag, conflictPolicy = policy)
                        world.trace += "enqueue ${handler.type} key=$key tag=$tag $policy -> ${id?.take(6)}"
                        if (id != null) {
                            world.enqueued[id] = tag
                            if (key != null) {
                                // the holder of the key (a replaced row, or a parked one a keep revives over) leaves without a withdraw
                                world.idByKey.put("${handler.type}/$key", id)?.let { world.replaced += it }
                            }
                        }
                    }
                    in 35..44 -> {
                        val victims: List<String> = world.enqueued.keys.filter { it !in world.withdrawn }
                        if (victims.isNotEmpty()) {
                            val id: String = victims[r.nextInt(victims.size)]
                            world.trace += "cancel ${id.take(6)}"
                            engine.cancel(id)
                            world.withdrawn += id
                        }
                    }
                    in 45..49 -> {
                        val tag = "tag${r.nextInt(3)}"
                        world.trace += "cancelByTag $tag"
                        engine.cancelByTag(tag)
                        world.enqueued.filterValues { it == tag }.keys.forEach { world.withdrawn += it }
                    }
                    in 50..57 -> {
                        world.trace += "restart engine"
                        engine.close()
                        engine = newEngine(world, storage, scope)
                    }
                    in 58..60 -> {
                        world.trace += "restart storage"
                        engine.close()
                        storage.close()
                        storage = createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
                        engine = newEngine(world, storage, scope)
                    }
                    in 61..72 -> {
                        val claims: List<Map.Entry<String, Long>> = world.detachedClaims.entries.toList()
                        if (claims.isNotEmpty()) {
                            val (id, claim) = claims[r.nextInt(claims.size)].let { it.key to it.value }
                            val stale: Boolean = r.nextInt(4) == 0
                            val result: SettleResult = if (r.nextBoolean()) SettleResult.Delivered else SettleResult.Failed(IllegalStateException("net"))
                            world.trace += "settle ${id.take(6)} ${result::class.simpleName} stale=$stale"
                            engine.settle(id, if (stale) claim - 1 else claim, result)
                            if (result is SettleResult.Delivered) world.delivered += id
                            // a delivery report lands whichever claim it carries (documented), so the row is gone either way
                        }
                    }
                    in 73..84 -> {
                        val jump: Long = when (r.nextInt(6)) {
                            0 -> 86_400_000L * (1 + r.nextInt(3))
                            1 -> 3_600_000L * (1 + r.nextInt(5))
                            2 -> -3_600_000L * r.nextInt(3)
                            else -> 1_000L * r.nextInt(120)
                        }
                        world.clockOffset.addAndGet(jump)
                        engine.trigger()
                    }
                    in 85..89 -> engine.trigger()
                    else -> delay(1L + r.nextInt(15))
                }
                if (step % 10 == 9) checkInvariants(world, storage, where)
            }
            checkInvariants(world, storage, "seed=$seed end of workload")

            // quiesce: time passes by days, every detached delivery is reported, the plain handlers' budget is finite
            world.clockOffset.addAndGet(40L * 86_400_000L)
            val deadline: Long = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                world.detachedClaims.entries.toList().forEach { (id, claim) ->
                    if (storage.store.getById(id)?.state == UploaderItemState.IN_FLIGHT) {
                        engine.settle(id, claim, SettleResult.Delivered)
                        world.delivered += id
                    }
                }
                engine.trigger()
                if (storage.store.getAllActive().isEmpty()) break
                delay(25)
            }
            val left: List<UploaderItem> = storage.store.getAllActive()
            assertTrue(left.isEmpty(), "seed=$seed the queue did not drain after 40 quiet days: ${left.take(5).map { "${it.id}/${it.state}/attempts=${it.attempts}/gate=${it.nextRunAtEpochMillis}/lease=${it.leaseUntilEpochMillis}" }}")
            // what remains in the table is exactly what was parked, with its reason, never a stuck PENDING or IN_FLIGHT row
            val remaining: List<String> = listOf("soak.plain", "soak.detached").flatMap { storage.store.observeByType(it).first() }.map { it.id }
            for (id in remaining) {
                val row: UploaderItem = checkNotNull(storage.store.getById(id))
                assertEquals(UploaderItemState.PARKED, row.state, "seed=$seed $id is left in ${row.state}")
                assertEquals(0L, row.leaseUntilEpochMillis, "seed=$seed parked row $id holds a lease")
            }
            assertTrue(world.parked.containsAll(remaining.filter { it in world.parked }), "seed=$seed")
        } finally {
            engine.close()
            storage.close()
            val children: Int = scope.coroutineContext[Job]!!.children.count { it.isActive }
            val settledBy: Long = System.currentTimeMillis() + 5_000L
            var stillActive: Int = children
            while (stillActive > 0 && System.currentTimeMillis() < settledBy) {
                delay(20)
                stillActive = scope.coroutineContext[Job]!!.children.count { it.isActive }
            }
            scope.cancel()
            assertEquals(0, stillActive, "seed=$seed coroutines of a closed engine are still running")
        }
        // leak check: process-wide state of the engine (reservations of claims in doubt, the registry), the database thread, files, descriptors
        val reservations: Map<*, *> = run {
            val holder: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.uploader.ClaimsInDoubt")
            val field = holder.getDeclaredField("held").apply { isAccessible = true }
            (field.get(holder.getField("INSTANCE").get(null)) as kotlinx.coroutines.flow.StateFlow<*>).value as Map<*, *>
        }
        assertTrue(reservations.isEmpty(), "seed=$seed claim reservations leaked: ${reservations.keys}")
        assertTrue(UploaderEngineRegistry.current == null, "seed=$seed a closed engine is still the registered one")
        val deadline: Long = System.currentTimeMillis() + 5_000L
        while (dbThreads() > threadsBefore && System.currentTimeMillis() < deadline) delay(20)
        assertEquals(threadsBefore, dbThreads(), "seed=$seed database threads leaked")
        val dbFile: File = application().getDatabasePath(uploaderDatabaseFileName(name))
        val leftovers: List<String> = (dbFile.parentFile?.list() ?: emptyArray()).filter { it.startsWith(dbFile.name) && it != dbFile.name }
        assertTrue(leftovers.none { it.endsWith("-journal") }, "seed=$seed a hot journal was left behind: $leftovers")
        val fdsAfter: Int = openFds()
        if (fdsBefore >= 0) assertTrue(fdsAfter <= fdsBefore + 8, "seed=$seed descriptors grew from $fdsBefore to $fdsAfter")
    }

    @Test
    fun `seeded soaks keep the queue invariants, drain after quiet days and leak nothing`() = runBlocking {
        val seconds: Long = (System.getenv("KMPTOOLKIT_R8_SOAK_SECONDS"))?.toLongOrNull() ?: 0L
        val firstSeed: Int = System.getenv("KMPTOOLKIT_R8_SOAK_SEED")?.toIntOrNull() ?: 1
        if (seconds <= 0L) {
            withTimeout(120.seconds) { for (seed in firstSeed until firstSeed + 3) soak(seed, steps = 250) }
        } else {
            val until: Long = System.currentTimeMillis() + seconds * 1_000L
            var seed: Int = firstSeed
            while (System.currentTimeMillis() < until) {
                withTimeout(300.seconds) { soak(seed, steps = 600) }
                seed++
            }
            println("R8 SOAK finished: seeds $firstSeed until $seed")
        }
    }
}
