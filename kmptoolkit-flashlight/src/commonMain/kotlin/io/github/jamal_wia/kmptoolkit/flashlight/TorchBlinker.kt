package io.github.jamal_wia.kmptoolkit.flashlight

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * - **A replacement does not clip its own first flash.** A cancelled loop turns the torch off in its
 *   `finally`, and that can run after the new loop has already switched it on. The new loop therefore
 *   waits for the old one to finish before its first "on".
 * - **[stop] always ends dark.** It detaches the running job and switches the torch off itself; a loop
 *   that was mid-"on" when cancelled reaches its `finally` at the next `delay` and switches it off
 *   again.
 *
 * The last call wins: a [stop] racing a [start] leaves the torch in whichever state the later of the
 * two asked for.
 *
 * @param scope where the loops run. Owned by the caller; nothing here cancels it.
 * @param setTorch switches the torch. Must not throw — platform failures are swallowed by the caller.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorchBlinker(
    private val scope: CoroutineScope,
    private val setTorch: (on: Boolean) -> Unit,
) {

    private val running: AtomicReference<Job?> = AtomicReference(null)

    /** Whether a loop is installed and has not finished. For tests; not part of any contract. */
    val isBlinking: Boolean get() = running.load()?.isActive == true

    fun start(pattern: FlashPattern) {
        // The job cannot know what it replaced until the swap below has happened, and the swap needs
        // the job — so the predecessor is handed over through a deferred rather than captured.
        val predecessor: CompletableDeferred<Job?> = CompletableDeferred()
        val next: Job = scope.launch {
            predecessor.await()?.cancelAndJoin()
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
        val previous: Job? = running.exchange(next)
        previous?.cancel()
        predecessor.complete(previous)
    }

    fun stop() {
        running.exchange(null)?.cancel()
        setTorch(false)
    }
}
