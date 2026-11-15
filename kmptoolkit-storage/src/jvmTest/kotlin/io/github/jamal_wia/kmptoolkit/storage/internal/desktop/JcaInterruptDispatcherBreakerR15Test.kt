package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 15 of group `sto-desktop` (review/sto-desktop-breaker-15), against task/sto-desktop 93bb4a41: the interrupt rule of
 * the JCA boundary (rule 1, round 10/11) as it meets the store's DEFAULT dispatcher.
 *
 * The contract under attack, in three places that say the same thing:
 * - 05-platform-notes.md: the interrupt status is put back on "a pool thread under `Dispatchers.IO`, where the pool clears the status
 *   before its next task", and "With the default `Dispatchers.IO` the I/O runs on a pool thread and an interrupted caller is served
 *   normally";
 * - the KDoc of `Jca` (rule 1): "a pooled dispatcher thread clears it before its next task";
 * - D-P12 (round-16 report): "a pool clears it before its next task".
 *
 * kotlinx.coroutines 1.11.0 does not: its scheduler clears a worker's interrupt status only when the worker PARKS
 * (`CoroutineScheduler.kt`, `interrupted() // Cleanup interruptions` in `tryPark`, and before a timed park), never between two tasks,
 * and a `limitedParallelism` view runs the tasks queued for it one after the other inside one `Runnable` on one thread
 * (`LimitedDispatcher.Worker.run`). So the interrupt that the boundary restores after a provider was interrupted in one store call is
 * still set when the next coroutine runs on that thread, and that coroutine, which nobody interrupted, gets its file I/O refused.
 *
 * Deterministic shape: a one-thread view of `Dispatchers.IO` is held by a blocker task (latch) while the interrupted call and the
 * victim call are queued behind it in that order; the blocker is then released, so both run on that same thread with no park in
 * between. The control runs the very same scenario on a `ThreadPoolExecutor` dispatcher, whose worker DOES clear the status before
 * each task (`ThreadPoolExecutor.runWorker`), and is green: the test turns red only through the dispatcher's rule, i.e. the claim.
 * The victim clears the status it inherited in a `finally`, so no pool thread of the test JVM keeps it.
 *
 * Provider: [B9ScriptGcmProvider] (round-9 breaker), which throws `InterruptedException` at a chosen decryption, the way a PKCS#11
 * token call does; inserted first and removed in `finally`.
 */
class JcaInterruptDispatcherBreakerR15Test {

    private class Outcome(
        val interrupted: StorageResult<String?>,
        val victimThread: Thread,
        val interruptedThread: Thread,
        val victimInterruptedOnEntry: Boolean,
        val victim: StorageResult<Unit>,
    )

    private fun <T> withScriptProvider(block: () -> T): T {
        B9ScriptGcmSpi.causeOnRealTagFailure = true
        B9ScriptGcmSpi.arm()
        check(Security.insertProviderAt(B9ScriptGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(B9ScriptGcmProvider.NAME)
            B9ScriptGcmSpi.arm()
        }
    }

    /**
     * A warm store with an entry and a second store of the same directory, both on [dispatcher] (one thread). The provider is
     * interrupted in a `get` of the first; a `put` of the second, queued right behind it, is the victim.
     */
    private fun scenario(dispatcher: CoroutineDispatcher): Outcome = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS, dispatcher = dispatcher).use { rig: SecureTestRig ->
                withScriptProvider {
                    runBlocking {
                        val store: EncryptedKeyValueStorage = rig.store()
                        val other: EncryptedKeyValueStorage = rig.store(name = OTHER)
                        assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: a warm store with an entry")
                        assertEquals(StorageResult.Success(Unit), other.put("o", "1"), "control: the second store works")
                        val before: ByteArray = rig.dataFile().readBytes()
                        val scope = CoroutineScope(SupervisorJob())
                        val holding = CountDownLatch(1)
                        val blocking = CountDownLatch(1)
                        try {
                            val blocker: Job = scope.launch(dispatcher) {
                                blocking.countDown()
                                check(holding.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the blocker" }
                            }
                            check(blocking.await(HANG_SECONDS, TimeUnit.SECONDS)) { "control: the blocker holds the dispatcher's thread" }
                            B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                            var interruptedThread: Thread? = null
                            val first: Deferred<StorageResult<String?>> = scope.async(dispatcher) {
                                interruptedThread = Thread.currentThread()
                                store.get("k")
                            }
                            val victim: Deferred<Triple<Thread, Boolean, StorageResult<Unit>>> = scope.async(dispatcher) {
                                val thread: Thread = Thread.currentThread()
                                val onEntry: Boolean = thread.isInterrupted
                                try {
                                    Triple(thread, onEntry, other.put("o", "2"))
                                } finally {
                                    // Never leave an interrupt on a pool thread of the test JVM.
                                    Thread.interrupted()
                                }
                            }
                            holding.countDown()
                            blocker.join()
                            val interrupted: StorageResult<String?> = first.await()
                            val (thread: Thread, onEntry: Boolean, answer: StorageResult<Unit>) = victim.await()
                            assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted once")
                            assertContentEquals(before, rig.dataFile().readBytes(), "control: the interrupted call changed the file")
                            Outcome(interrupted, thread, checkNotNull(interruptedThread), onEntry, answer)
                        } finally {
                            holding.countDown()
                            scope.cancel()
                        }
                    }
                }
            }
        }
    }

    /**
     * RED on 93bb4a41. Mutation by reading: a boundary that does not put the status back, a store that clears it before its
     * dispatch ends, or a dispatcher that clears it between tasks (what the three texts say `Dispatchers.IO` does) turns it green.
     */
    @Test
    fun `a call nobody interrupted is served normally on the Dispatchers IO thread right after a provider was interrupted there`() {
        val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
        val outcome: Outcome = scenario(io)
        assertSame(outcome.interruptedThread, outcome.victimThread, "control: both calls ran on the same thread of the Dispatchers.IO view")
        assertEquals(
            StorageResult.Success(Unit),
            outcome.victim,
            "05: on Dispatchers.IO 'the pool clears the status before its next task' and a caller is 'served normally'; but the next " +
                "coroutine on that thread started with the interrupt status ${outcome.victimInterruptedOnEntry} (restored by the boundary " +
                "for the earlier call, answered ${outcome.interrupted}) and a put that nobody interrupted was refused",
        )
    }

    /** Control, GREEN: the same scenario on a `ThreadPoolExecutor`, which clears the status before every task. */
    @Test
    fun `control - on a thread pool executor that clears the status between tasks the next call is served normally`() {
        val pool: ExecutorService = Executors.newSingleThreadExecutor()
        try {
            val outcome: Outcome = scenario(pool.asCoroutineDispatcher())
            assertSame(outcome.interruptedThread, outcome.victimThread, "control: both calls ran on the pool's one thread")
            assertEquals(false, outcome.victimInterruptedOnEntry, "control: ThreadPoolExecutor clears the status before a task")
            assertEquals(StorageResult.Success(Unit), outcome.victim, "the next call on a clearing pool")
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val OTHER: String = "other"
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND: Duration = 180.seconds
    }
}
