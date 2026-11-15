package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the single loop of one throttled collection receives; see [ReadingThrottle]. */
private sealed interface Inbound {
    /** A reading from the upstream. */
    class Received(val reading: LocationReading) : Inbound

    /** The pending-fix timer of emission number [generation] reached its deadline. */
    class Tick(val generation: Int) : Inbound

    /** The upstream completed. */
    data object Completed : Inbound
}

/**
 * The common throttle of [LocationUpdatesOptions.minUpdateInterval] and
 * [LocationUpdatesOptions.minUpdateDistanceMeters], shared by every platform provider (iOS relies on
 * it alone for the interval; Android also hands both values to the platform request). It is a
 * trailing-edge throttle: a fix that the interval alone holds back is not lost, it is emitted when
 * the interval ends.
 *
 * The contract (PLAN D16, M10, review M1 of round 1):
 * - an [LocationReading.Unavailable] is never delayed and never dropped, whatever the interval or
 *   the distance; it also discards the pending fix (no fix may follow a reason that it predates)
 *   and forgets the reference, so the next fix passes at once;
 * - the distance is measured from the last **emitted** fix, not from the last one received;
 * - a fix passes at once when the interval since the last emitted fix is over and it is at least
 *   the minimum distance from that fix;
 * - a fix that is far enough but arrives inside the interval becomes the **pending** fix and is
 *   emitted exactly when the interval ends, so a user who moved and then stopped is never left on a
 *   stale position, and a platform delivery that jitters a little early is delayed, not dropped;
 *   a newer such fix replaces the pending one (the newest wins);
 * - a fix that is too close to the last emitted one is dropped and also discards the pending fix: the
 *   newest position says nothing worth emitting (the user came back);
 * - a **seed** (the cached first fix of a stream, recognised by `isSeed`, decision D-21) sets the
 *   distance reference but no emission time: the interval has not started, so the first live fix
 *   that is far enough passes at once, and one that is too close is dropped like any other;
 * - when the upstream completes while a fix is pending, that fix is still emitted at the end of the
 *   interval and then the stream completes.
 *
 * Concurrency (the actor shape): one loop owns all the state (reference fix, pending fix, timer) and
 * is the only code that emits. The upstream is collected by a child coroutine and the interval timer
 * is another; both only put a message in the loop's channel, they never touch the state, so no lock
 * exists and no decision is made while another coroutine is deciding. The loop suspends only in
 * `receive` and in `emit`; messages wait in the bounded channel meanwhile, so a slow consumer slows
 * the upstream (backpressure) and a reading, a tick and the end of the upstream keep their order. A
 * tick of an earlier emission is fenced by its generation number, so a timer that was cancelled too
 * late cannot flush a newer pending fix early. Cancelling the collector cancels the whole scope: the
 * upstream collection (releasing the platform registration behind it) and the timer; nothing is
 * left behind, there is no `NonCancellable` and no half-written state.
 */
private class ReadingThrottle(
    private val scope: CoroutineScope,
    private val out: FlowCollector<LocationReading>,
    private val inbox: Channel<Inbound>,
    private val minDistanceMeters: Double,
    private val minInterval: Duration,
    private val timeSource: TimeSource,
    private val isSeed: (LocationReading) -> Boolean,
) {
    private var lastEmittedFix: LocationFix? = null
    /** `null` while there is no reference fix and while the reference is a seed (no interval running). */
    private var lastEmittedAt: TimeMark? = null
    private var pending: LocationFix? = null
    private var generation: Int = 0
    private var timer: Job? = null
    private var upstreamCompleted: Boolean = false

    /** Whether the loop has nothing left to do: the upstream is over and no fix waits for its deadline. */
    val isFinished: Boolean
        get() = upstreamCompleted && pending == null

    suspend fun handle(message: Inbound) {
        when (message) {
            is Inbound.Received -> handleReading(message.reading)
            is Inbound.Tick -> {
                // A tick of an earlier emission is stale; one that matches ends the interval.
                val waiting: LocationFix? = pending
                if (message.generation == generation && waiting != null) emitFix(waiting)
            }
            Inbound.Completed -> upstreamCompleted = true
        }
    }

    /**
     * A deadline that the clock has passed but whose tick is still queued behind the message being
     * handled is honoured first, so the order of a message and a deadline at the same instant never
     * matters: the pending fix is emitted, then the message is judged against it.
     */
    private suspend fun flushDueFix() {
        val waiting: LocationFix? = pending
        val reference: TimeMark? = lastEmittedAt
        if (waiting != null && reference != null && reference.elapsedNow() >= minInterval) emitFix(waiting)
    }

    private suspend fun handleReading(reading: LocationReading) {
        flushDueFix()
        when (reading) {
            is LocationReading.Unavailable -> {
                // A reason passes at once, discards the pending fix and the reference: the next fix ends it.
                cancelTimer()
                pending = null
                lastEmittedFix = null
                lastEmittedAt = null
                out.emit(reading)
            }
            is LocationReading.Available -> handleFix(reading)
        }
    }

    private suspend fun handleFix(reading: LocationReading.Available) {
        val previousFix: LocationFix? = lastEmittedFix
        if (previousFix == null) {
            emitFix(reading.fix, asSeed = isSeed(reading))
            return
        }
        val farEnough: Boolean =
            previousFix.coordinates.distanceInMetersTo(reading.fix.coordinates) >= minDistanceMeters
        // No emission time means the reference is a seed: the interval has not started.
        val previousAt: TimeMark? = lastEmittedAt
        when {
            !farEnough -> {
                cancelTimer()
                pending = null
            }
            previousAt == null || previousAt.elapsedNow() >= minInterval -> emitFix(reading.fix, asSeed = isSeed(reading))
            else -> {
                pending = reading.fix
                startTimerIfIdle(previousAt)
            }
        }
    }

    private suspend fun emitFix(fix: LocationFix, asSeed: Boolean = false) {
        cancelTimer()
        pending = null
        lastEmittedFix = fix
        lastEmittedAt = if (asSeed) null else timeSource.markNow()
        out.emit(LocationReading.Available(fix))
    }

    private fun startTimerIfIdle(emittedAt: TimeMark) {
        if (timer?.isActive == true) return
        val forGeneration: Int = generation
        val remaining: Duration = (minInterval - emittedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
        timer = scope.launch {
            delay(remaining)
            inbox.send(Inbound.Tick(forGeneration))
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
        generation++
    }

    /** Cancels the timer when the loop ends; the upstream child is cancelled by the caller. */
    fun close() {
        timer?.cancel()
        timer = null
    }
}

/**
 * Applies [options]' `minUpdateInterval` and `minUpdateDistanceMeters` to this stream of readings;
 * see [ReadingThrottle] for the rules. Cold: each collection starts with no reference fix, so the
 * first fix of every collection is emitted at once.
 *
 * @param isSeed marks the cached first fix of a stream (default: nothing is a seed). A seed is emitted
 * like any first fix but starts no interval, so the first far-enough live fix after it is not held
 * behind a cached position; platform providers pass `{ reading -> reading === seed }`.
 * @param timeSource the clock the interval is measured on; tests pass the test scheduler's, so the
 * timer (`delay`, virtual in `runTest`) and the clock agree.
 */
internal fun Flow<LocationReading>.throttledBy(
    options: LocationUpdatesOptions,
    timeSource: TimeSource = TimeSource.Monotonic,
    isSeed: (LocationReading) -> Boolean = { false },
): Flow<LocationReading> {
    val upstream: Flow<LocationReading> = this
    return flow {
        coroutineScope {
            val inbox: Channel<Inbound> = Channel(Channel.BUFFERED)
            val throttle = ReadingThrottle(
                scope = this,
                out = this@flow,
                inbox = inbox,
                minDistanceMeters = options.minUpdateDistanceMeters,
                minInterval = options.minUpdateInterval,
                timeSource = timeSource,
                isSeed = isSeed,
            )
            // An upstream failure cancels this scope and is rethrown by it; no catch is needed.
            val pump: Job = launch {
                upstream.collect { reading: LocationReading -> inbox.send(Inbound.Received(reading)) }
                inbox.send(Inbound.Completed)
            }
            try {
                while (!throttle.isFinished) {
                    throttle.handle(inbox.receive())
                }
            } finally {
                throttle.close()
                pump.cancel()
            }
        }
    }
}
