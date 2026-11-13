package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxHost
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxQueue
import java.util.PriorityQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The FX application thread, its clock and its timers on one thread and virtual time, for the model tier.
 *
 * ORDERING IS THE PRODUCTION RULE: [post], [postLater] and [call] go through a real [FxQueue] over this
 * host's queue, so the inline-or-queued decision is the production code, not a copy of it.
 *
 * "THE FX THREAD" is the driving thread while it runs a queued block ([step], [advanceBy], [runUntilIdle])
 * or a block handed to [onFx], and nothing else. A call made outside those, from the test body or from any
 * other thread, is a call from another thread: it is always queued, never inline, and runs when the test
 * drives time. Nothing runs by itself; no real time passes.
 *
 * CLOCK: [nanoTime] is [epochNanos] plus the virtual time elapsed, which moves only when a queued block
 * with a later due time is taken. The default epoch is [DEFAULT_EPOCH_NANOS]; `NegativeClockModelTest` runs the
 * scenarios of the player on a negative epoch, on 0 and near `Long.MAX_VALUE` too, because `System.nanoTime()` may
 * start anywhere and `FxPlayback` may depend on none of it.
 *
 * QUEUE: one priority queue ordered by due time, then by the order of enqueueing. A block queued by
 * [post], [postLater] or [call] is due now; [after] is due `delay` later, plus a seeded jitter of up to
 * [jitterMaxMs] (a `PauseTransition` fires on a pulse, not on the dot).
 *
 * THREADS: the queue and the clock are safe to read and to enqueue into from any thread (the exit watch
 * in `FxPlayback.open` and a test's helper thread both do); only the driving thread runs blocks, and
 * driving from inside a running block is refused. [hasExitedNow] is always `false` and thread-safe.
 *
 * FAILURES: a block that throws propagates out of the call that was driving it, after the host has
 * restored its own state; the block is gone from the queue. [seed] and [toString] are in every failure
 * message of this class so a failing run can be repeated.
 *
 * @param epochNanos the value [nanoTime] has before any time passes.
 * @param seed seeds the [after] jitter; defaults to [DEFAULT_SEED], or `-Dkmptoolkit.test.seed` when set.
 * @param jitterMaxMs the most milliseconds [after] adds to a delay (the draw is in `0..jitterMaxMs`,
 *   whole nanoseconds); 0 turns the jitter off, which is the default.
 * @param maxTasksPerCall the most blocks one driving call runs before it fails as a block that keeps queueing
 *   itself; a parameter so a test of that failure stays fast.
 */
internal class VirtualFxHost(
    val epochNanos: Long = DEFAULT_EPOCH_NANOS,
    val seed: Long = defaultSeed(),
    val jitterMaxMs: Double = 0.0,
    private val maxTasksPerCall: Int = MAX_TASKS_PER_CALL,
) : FxHost {

    init {
        require(jitterMaxMs >= 0.0 && jitterMaxMs.isFinite()) { "jitterMaxMs must be finite and not negative, was $jitterMaxMs" }
    }

    private class Task(val dueNanos: Long, val sequence: Long, val block: () -> Unit)

    private val lock: Any = Any()
    private val tasks: PriorityQueue<Task> = PriorityQueue(
        compareBy<Task> { it.dueNanos }.thenBy { it.sequence },
    )
    private val jitter: Random = Random(seed)
    private var nextSequence: Long = 0L

    /** Virtual nanoseconds elapsed since construction. Written only by the driving thread. */
    @Volatile
    private var elapsed: Long = 0L

    /** The thread that is "the FX thread" right now, or null. */
    @Volatile
    private var fxThread: Thread? = null

    /** The thread that is driving the queue, or null. Guards against driving from inside a block. */
    @Volatile
    private var driver: Thread? = null

    private var beforeEachTask: ((taskIndex: Int) -> Unit)? = null

    /** How many blocks the queue has run so far; the index [beforeEachTask] is called with. */
    var tasksRun: Int = 0
        private set

    private val queue: FxQueue = FxQueue(
        runLater = { block -> enqueue(0L) { block.run() } },
        isFxThread = { fxThread === Thread.currentThread() },
        onEdge = { },
    )

    private val registered: MutableList<ScriptedNativePlayer> = CopyOnWriteArrayList()

    /**
     * Every [ScriptedNativePlayer] built on this host so far, in creation order: each registers itself when it is built
     * (see [register]), so the end-of-test guard of `fxModel` can tell which of them no hazard part watches.
     */
    val players: List<ScriptedNativePlayer> get() = registered.toList()

    /** Called by a [ScriptedNativePlayer] as the last step of its construction; nothing else registers a player. */
    internal fun register(player: ScriptedNativePlayer) {
        registered += player
    }

    /** Virtual nanoseconds elapsed since construction. */
    val elapsedNanos: Long get() = elapsed

    /** How many blocks are queued and not run yet, whatever their due time. */
    val pendingCount: Int get() = synchronized(lock) { tasks.size }

    /** The virtual time of the earliest queued block as an [nanoTime] value, or null when the queue is empty. */
    val nextDueNanos: Long? get() = synchronized(lock) { tasks.peek()?.let { epochNanos + it.dueNanos } }

    /** Whether the calling thread is "the FX thread" right now: inside a running block or an [onFx] block. */
    val isFxThread: Boolean get() = fxThread === Thread.currentThread()

    override fun post(block: () -> Unit) = queue.post(block)

    override fun postLater(block: () -> Unit) = queue.postLater(block)

    override suspend fun <T> call(block: () -> T): T = queue.call(block)

    override fun nanoTime(): Long = epochNanos + elapsed

    override fun after(delayMillis: Double, block: () -> Unit) {
        require(delayMillis >= 0.0 && delayMillis.isFinite()) { "delayMillis must be finite and not negative, was $delayMillis ($this)" }
        val jitterNanos: Long = if (jitterMaxMs > 0.0) synchronized(lock) { (jitter.nextDouble() * jitterMaxMs * NANOS_PER_MILLI).toLong() } else 0L
        enqueue((delayMillis * NANOS_PER_MILLI).toLong() + jitterNanos, block)
    }

    /**
     * [after] without the jitter, and without drawing from the seeded sequence: for a timer that IS a pulse (the picture
     * part's), which the real FX thread fires on the exact pulse instant and which is scheduled from a nominal grid, so a
     * late fire would put the next grid point in the past. The other timers keep the jitter of [after].
     */
    fun afterExact(delayMillis: Double, block: () -> Unit) {
        require(delayMillis >= 0.0 && delayMillis.isFinite()) { "delayMillis must be finite and not negative, was $delayMillis ($this)" }
        enqueue((delayMillis * NANOS_PER_MILLI).toLong(), block)
    }

    override fun hasExitedNow(): Boolean = false

    /**
     * Runs [block] as the FX thread, inline and at once, then returns its result without running anything
     * queued. Calls it makes through [post] take the inline path when nothing is queued, as on the real FX
     * thread. Not for blocks that wait for the queue: nothing drains it meanwhile.
     */
    fun <T> onFx(block: () -> T): T = asFxThread(block)

    /**
     * Installs [hook], called on the driving thread, not as the FX thread, before every queued block is
     * run, with the number of blocks run so far. Calls the hook makes are therefore "from another thread":
     * queued behind what is already due, so a script of calls can be injected at every point between two
     * blocks. The hook runs with the clock where the previous block left it, and may queue a block that
     * is due before the next one; that block then runs first. `null` removes it. Replaces any earlier hook.
     */
    fun beforeEachTask(hook: ((taskIndex: Int) -> Unit)?) {
        beforeEachTask = hook
    }

    /**
     * Runs the earliest queued block, moving the clock to its due time if that is in the future. Returns
     * false, and changes nothing, when the queue is empty.
     */
    fun step(): Boolean = drive { runNext(limitDueNanos = Long.MAX_VALUE) }

    /**
     * Runs every block due within [millis] of now, in order, including blocks they queue inside the window,
     * then sets the clock exactly [millis] later. Fails after `maxTasksPerCall` blocks, which is a block
     * that keeps queueing itself without time passing.
     */
    fun advanceBy(millis: Long) {
        require(millis >= 0L) { "millis must not be negative, was $millis ($this)" }
        advanceByNanos(millis * NANOS_PER_MILLI)
    }

    /** [advanceBy] in nanoseconds. */
    fun advanceByNanos(nanos: Long) {
        require(nanos >= 0L) { "nanos must not be negative, was $nanos ($this)" }
        drive {
            val limit: Long = elapsed + nanos
            var run = 0
            while (runNext(limitDueNanos = limit)) failIfRunaway(++run, "advanceBy")
            elapsed = limit
        }
    }

    /**
     * Runs queued blocks until none is left. A queue that never empties on its own (a pulse, a poller) is
     * cut at [limitMs] of virtual time from now, and that is a failure: the caller named an idle point that
     * does not exist. A block that keeps queueing itself without time passing fails after
     * `maxTasksPerCall` blocks.
     */
    fun runUntilIdle(limitMs: Long = DEFAULT_IDLE_LIMIT_MS) {
        require(limitMs >= 0L) { "limitMs must not be negative, was $limitMs ($this)" }
        drive {
            val limit: Long = elapsed + limitMs * NANOS_PER_MILLI
            var run = 0
            while (runNext(limitDueNanos = limit)) failIfRunaway(++run, "runUntilIdle")
            val left: Int = pendingCount
            check(left == 0) { "runUntilIdle: $left block(s) still queued $limitMs ms of virtual time after the start, never idle ($this)" }
        }
    }

    override fun toString(): String =
        "VirtualFxHost(seed=$seed, epochNanos=$epochNanos, elapsedNanos=$elapsed, pending=$pendingCount, tasksRun=$tasksRun)"

    /** Runs one block of the queue if its due time is within [limitDueNanos]; returns whether one ran. */
    private fun runNext(limitDueNanos: Long): Boolean {
        val upcoming: Task = synchronized(lock) { tasks.peek() } ?: return false
        if (upcoming.dueNanos > limitDueNanos) return false
        // The hook sees the clock where the last block left it, and may queue something due earlier.
        beforeEachTask?.invoke(tasksRun)
        // Never null and never past the limit: the hook only adds blocks, so the earliest one is still
        // the upcoming one or an earlier one.
        val next: Task = synchronized(lock) { tasks.poll() } ?: return false
        if (next.dueNanos > elapsed) elapsed = next.dueNanos
        tasksRun++
        asFxThread(next.block)
        return true
    }

    private fun failIfRunaway(run: Int, what: String) {
        check(run < maxTasksPerCall) { "$what: $maxTasksPerCall blocks ran without the queue emptying or the window ending, a block keeps queueing itself ($this)" }
    }

    private fun enqueue(delayNanos: Long, block: () -> Unit) {
        synchronized(lock) { tasks.add(Task(elapsed + delayNanos, nextSequence++, block)) }
    }

    private fun <T> asFxThread(block: () -> T): T {
        val previous: Thread? = fxThread
        fxThread = Thread.currentThread()
        try {
            return block()
        } finally {
            fxThread = previous
        }
    }

    private fun <T> drive(body: () -> T): T {
        val me: Thread = Thread.currentThread()
        check(driver == null) { "the host is already being driven, from ${driver?.name}: a block cannot drive time ($this)" }
        driver = me
        try {
            return body()
        } finally {
            driver = null
        }
    }

    companion object {
        /** A clock far from 0 and from `Long.MAX_VALUE`: one thousand seconds. */
        const val DEFAULT_EPOCH_NANOS: Long = 1_000_000_000_000L

        /** The fixed default seed of every random choice of the model tier. */
        const val DEFAULT_SEED: Long = 20261113L

        /** [DEFAULT_SEED], or the value of `-Dkmptoolkit.test.seed` when the property is set. */
        fun defaultSeed(): Long = java.lang.Long.getLong("kmptoolkit.test.seed", DEFAULT_SEED)

        /** The default [runUntilIdle] horizon, virtual milliseconds. */
        const val DEFAULT_IDLE_LIMIT_MS: Long = 60_000L

        /** More blocks than this in one driving call is a block that queues itself forever. */
        const val MAX_TASKS_PER_CALL: Int = 1_000_000

        private const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
