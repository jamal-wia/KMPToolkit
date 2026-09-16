package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The blink loop both platform [Flashlight]s share, and the one place their thread-safety is
 * decided.
 *
 * The platforms differ only in how the torch is switched — that is [setTorch]. Everything that can
 * go wrong between two callers lives here, once:
 *
 * - **Concurrent [start]s cannot orphan a loop.** The running job is swapped atomically, so whichever
 *   call lands second always sees, and cancels, the job the first one installed. With a plain field,
 *   two starts racing could both read the same old job and each install their own — leaving one loop
 *   blinking that no [stop] could reach.
 * - **Nothing clips a new pattern's first flash.** A cancelled loop turns the torch off in its
 *   `finally`, and on a multi-threaded dispatcher that can run after the next loop has already
 *   switched it on. So every job this class installs — a loop from [start], and the switch-off from
 *   [stop] — first waits for the job it replaced to finish, and a [start] after a [stop] waits for the
 *   stop's switch-off in turn. The wait cannot be cut short by the waiting job being replaced itself:
 *   otherwise the job after it would stop waiting for the one still pending.
 * - **[stop] ends dark.** It switches the torch off at once, and again once the loop it cancelled has
 *   finished — a loop that was mid-"on" reaches its own `finally` at the next `delay`.
 *
 * The last call wins: a [stop] racing a [start] from another thread leaves the torch in whichever
 * state the later of the two asked for.
 *
 * @param scope where the jobs run. Owned by the caller; nothing here cancels it.
 * @param setTorch switches the torch. Must not throw — platform failures are swallowed by the caller.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorchBlinker(
    private val scope: CoroutineScope,
    private val setTorch: (on: Boolean) -> Unit,
) {

    /** The most recently installed job; [Phase.isLoop] tells a blink loop from a stop's switch-off. */
    private class Phase(val job: Job, val isLoop: Boolean)

    private val current: AtomicReference<Phase?> = AtomicReference(null)

    /** Whether a blink loop is installed and has not finished. For tests; not part of any contract. */
    val isBlinking: Boolean
        get() = current.load()?.let { phase: Phase -> phase.isLoop && phase.job.isActive } == true

    fun start(pattern: FlashPattern) {
        install(isLoop = true) {
            try {
                while (isActive) {
                    setTorch(true)
                    delay(pattern.on)
                    setTorch(false)
                    delay(pattern.off)
                }
            } finally {
                // Cancellation lands mid-cycle as often as not; the torch must never be left burning.
                setTorch(false)
            }
        }
    }

    fun stop() {
        setTorch(false)
        install(isLoop = false) { setTorch(false) }
    }

    /**
     * Launches [body] to run once the job it replaces has finished, and makes it the current job.
     *
     * The job cannot know what it replaced until the swap has happened, and the swap needs the job — so
     * the predecessor is handed over through a deferred rather than captured.
     */
    private fun install(isLoop: Boolean, body: suspend CoroutineScope.() -> Unit) {
        val predecessor: CompletableDeferred<Job?> = CompletableDeferred()
        // UNDISPATCHED so the job is inside its non-cancellable wait before this function returns. A job
        // replaced before a dispatcher ever ran it would otherwise never start at all — and finish at
        // once, releasing its own successor without having waited for anything.
        val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { predecessor.await()?.cancelAndJoin() }
            body()
        }
        val previous: Phase? = current.exchange(Phase(job, isLoop))
        previous?.job?.cancel()
        predecessor.complete(previous?.job)
    }
}
