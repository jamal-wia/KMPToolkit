package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * A [BarStripReader] that returns whatever the test scripted, one step per read, then [fallback]
 * forever. It counts what a leak or an overlap would show: reads started, reads in flight (and the
 * most that were ever in flight at once), reads that ended by cancellation, and the draw hook calls.
 *
 * The counters are atomic and the queues thread-safe although the host tests run on one thread: a
 * test double that trips over its own instrumentation proves nothing about the code under test.
 */
internal class ScriptedBarStripReader(
    private val fallback: Step = Step.Styles(BarStripStyles(null, null)),
) : BarStripReader {

    sealed interface Step {
        /** A successful read of these styles. */
        class Styles(val styles: BarStripStyles) : Step

        /** A read that has nothing to report (a skip). */
        data object Null : Step

        /** A read that throws [error]. */
        class Throw(val error: Throwable) : Step

        /** A read that parks on a continuation exposed as [parked] until the test completes it. */
        data object Park : Step

        /** A read that waits [frames] composed frames, then behaves as [then]. */
        class AfterFrames(val frames: Int, val then: Step) : Step
    }

    private val steps: ConcurrentLinkedQueue<Step> = ConcurrentLinkedQueue()

    val reads: AtomicInteger = AtomicInteger()
    val inFlight: AtomicInteger = AtomicInteger()
    val maxInFlight: AtomicInteger = AtomicInteger()
    val cancelledReads: AtomicInteger = AtomicInteger()
    val draws: AtomicInteger = AtomicInteger()

    /** The `(statusPx, navPx)` of every read, in order. */
    val calls: CopyOnWriteArrayList<Pair<Int, Int>> = CopyOnWriteArrayList()

    /** `read-start`, `read-end` (normal or thrown) and `read-cancelled`, in the order they happened. */
    val events: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** The continuation of the read currently parked by a [Step.Park], or the last one that was. */
    @Volatile
    var parked: CancellableContinuation<BarStripStyles?>? = null
        private set

    fun enqueue(vararg step: Step) {
        steps.addAll(step)
    }

    /** Completes the parked read, as a late platform callback would. A no-op on a cancelled read. */
    fun completeParked(styles: BarStripStyles?) {
        checkNotNull(parked) { "no read was ever parked" }.resume(styles)
    }

    override fun ContentDrawScope.drawContentAndCapture() {
        draws.incrementAndGet()
        drawContent()
    }

    override suspend fun read(statusPx: Int, navPx: Int): BarStripStyles? {
        reads.incrementAndGet()
        calls.add(statusPx to navPx)
        val now: Int = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        events.add("read-start")
        try {
            val result: BarStripStyles? = run(steps.poll() ?: fallback)
            events.add("read-end")
            return result
        } catch (e: CancellationException) {
            cancelledReads.incrementAndGet()
            events.add("read-cancelled")
            throw e
        } catch (e: Throwable) {
            events.add("read-end")
            throw e
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun run(step: Step): BarStripStyles? = when (step) {
        is Step.Styles -> step.styles
        Step.Null -> null
        is Step.Throw -> throw step.error
        Step.Park -> suspendCancellableCoroutine { continuation -> parked = continuation }
        is Step.AfterFrames -> {
            repeat(step.frames) { withFrameMillis { } }
            run(step.then)
        }
    }
}

/**
 * The reader the lifecycle test installs: it succeeds at once with "no decision for either bar"
 * ([BarStripStyles] of two nulls, which publishes [SystemBarsOverride.None] over the initial
 * `None` — a no-op) and draws the content as it is. It reproduces, under Robolectric, the booking
 * the sampler had before the readers existed (every sample a success), without a layer or a
 * window copy that Robolectric cannot honour.
 */
internal class AlwaysSucceedsReader : BarStripReader {

    override fun ContentDrawScope.drawContentAndCapture() {
        drawContent()
    }

    override suspend fun read(statusPx: Int, navPx: Int): BarStripStyles? = BarStripStyles(null, null)
}
