package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Group sto-desktop, breaker round 16: attacks on the round-17 fix of the interrupt rule ([InterruptHygiene], [withStoreContext]).
 * Contract under attack (05 "Interrupted provider", the `Jca` KDoc rule 1, D-P12 of round 17): a store call never leaves an interrupt
 * status on a thread that it found clear; the work that follows on that thread INSIDE the store call sees the status, and "a caller
 * resumed on another thread does not".
 *
 * The element clears when kotlinx.coroutines ends the slice (`restoreThreadContext`), and that is not always before foreign code runs
 * on the thread: when the block of a dispatched `withContext` completes, kotlinx resumes a caller whose dispatcher needs no dispatch
 * (`Dispatchers.Unconfined`, `UnconfinedTestDispatcher`, an immediate dispatcher) INLINE, inside the same `DispatchedTask.run`, before
 * that task's `restoreThreadContext` runs (`DispatchedCoroutine.afterResume` -> `resumeCancellableWith` -> `runUnconfinedEventLoop`).
 * The caller's code after the store call, and any unconfined coroutine it resumes, then runs on the store's worker with the status
 * that the boundary put back: its interruptible I/O is refused (`ClosedByInterruptException`).
 *
 * Defect classes: stray interrupt reaching the caller after the call (tests 1 and 2, mutation by reading: they are green when the
 * status is cleared at the end of the block, inside the slice, as well as when the slice ends); the undispatched path (test 3, which
 * kotlinx covers through `UndispatchedCoroutine.threadStateToRecover`, red if the element only cleared on the next slice start); a
 * seeded storm on `Dispatchers.IO` (test 4, red when any slice end, exception path or cancellation path leaves the status behind).
 */
class InterruptHygieneBreakerR16Test {

    /** One raw worker thread: runs queued tasks back to back and never clears the interrupt status between them. */
    private class Worker(name: String) {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val stopped = AtomicBoolean()
        private val thread: Thread = Thread {
            while (!stopped.get()) {
                val next: Runnable? = tasks.poll()
                if (next == null) LockSupport.park(this) else next.run()
            }
        }.apply {
            this.name = name
            isDaemon = true
            start()
        }

        val id: Thread get() = thread

        fun post(task: Runnable) {
            tasks.add(task)
            LockSupport.unpark(thread)
        }

        fun interrupted(): Boolean {
            val answer = CompletableFuture<Boolean>()
            post { answer.complete(Thread.currentThread().isInterrupted) }
            return answer.get(HANG_SECONDS, TimeUnit.SECONDS)
        }

        fun clear() {
            val done = CompletableFuture<Boolean>()
            post { done.complete(Thread.interrupted()) }
            done.get(HANG_SECONDS, TimeUnit.SECONDS)
        }

        fun stop() {
            stopped.set(true)
            LockSupport.unpark(thread)
            thread.join(HANG_SECONDS * 1000)
        }
    }

    /** A dispatcher over [count] raw workers: the n-th dispatch goes to worker `min(n, count - 1)`. */
    private class Workers(count: Int) : AutoCloseable {
        val threads: List<Worker> = List(count) { index: Int -> Worker("breaker-r16-$index") }
        private val dispatches = AtomicInteger()

        val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                threads[minOf(dispatches.getAndIncrement(), threads.size - 1)].post(block)
            }
        }

        override fun close() {
            threads.forEach { it.stop() }
        }
    }

    /** A random source whose draw is interrupted (a PKCS#11 token call, a blocking entropy read). */
    private class Interrupted : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            throw InterruptedException("breaker R16: the provider call was interrupted")
        }
    }

    /** One provider interrupt through the boundary; answers whether the boundary put the status back (the control). */
    private fun provoke(): Boolean {
        val failure: Throwable? = runCatching { Jca.randomBytes(Interrupted(), 8) }.exceptionOrNull()
        check(failure is JcaFailure && failure.cause is InterruptedException) { "control: the provider's interrupt is typed: $failure" }
        return Thread.currentThread().isInterrupted
    }

    /** An interruptible read (a `FileChannel`, like the stores' own I/O): refused with `ClosedByInterruptException` on an interrupted thread. */
    private fun readThroughChannel(file: File): Int =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel: FileChannel -> channel.read(ByteBuffer.allocate(64)) }

    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(HANG_BOUND) { block() } }

    private inline fun <T> inDirectory(block: (File) -> T): T {
        val directory: File = Files.createTempDirectory("kmptoolkit-breaker-r16").toFile()
        try {
            return block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an Unconfined caller resumed by the end of a store call does not run with the status the boundary put back`() {
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            Workers(1).use { workers: Workers ->
                var controlRestored = false
                var callerThread: Thread? = null
                var callerSaw: Boolean? = null
                var callerRead: Result<Int>? = null
                bounded {
                    withContext(Dispatchers.Unconfined) {
                        withStoreContext(workers.dispatcher) { controlRestored = provoke() }
                        // The store call has returned. 05: "a caller resumed on another thread does not" see the status.
                        callerThread = Thread.currentThread()
                        callerSaw = Thread.currentThread().isInterrupted
                        callerRead = runCatching { readThroughChannel(probe) }
                        Thread.interrupted()
                    }
                }
                assertTrue(controlRestored, "control: the boundary put the status back inside the store call")
                assertEquals(workers.threads[0].id, callerThread, "control: the Unconfined caller resumed on the store's worker")
                assertFalse(workers.threads[0].interrupted(), "control: the worker is clear once the task ended")
                assertEquals(
                    false,
                    callerSaw,
                    "the caller's code after the store call ran with the interrupt status that the boundary put back for the store's work " +
                        "(the slice is restored only after kotlinx resumed the Unconfined caller inline); its file read: ${callerRead?.exceptionOrNull()}",
                )
                assertTrue(callerRead?.isSuccess == true, "the caller's own file read after the store call was refused: ${callerRead?.exceptionOrNull()}")
            }
        }
    }

    /**
     * The victim is not the caller: an unrelated coroutine on `Dispatchers.Unconfined` that waits for a signal. The caller only
     * signals it after the store call, and kotlinx runs the woken coroutine in the same unconfined event loop, still inside the
     * store's slice on the store's worker.
     */
    @Test
    fun `an unrelated Unconfined coroutine woken after a store call does not meet the status the boundary put back`() {
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            Workers(1).use { workers: Workers ->
                var controlRestored = false
                var victimThread: Thread? = null
                var victimSaw: Boolean? = null
                var victimRead: Result<Int>? = null
                bounded {
                    val signal = CompletableDeferred<Unit>()
                    val victim = launch(Dispatchers.Unconfined) {
                        signal.await()
                        victimThread = Thread.currentThread()
                        victimSaw = Thread.currentThread().isInterrupted
                        victimRead = runCatching { readThroughChannel(probe) }
                    }
                    withContext(Dispatchers.Unconfined) {
                        withStoreContext(workers.dispatcher) { controlRestored = provoke() }
                        signal.complete(Unit)
                    }
                    victim.join()
                }
                assertTrue(controlRestored, "control: the boundary put the status back inside the store call")
                assertEquals(workers.threads[0].id, victimThread, "control: the woken coroutine ran on the store's worker")
                assertFalse(workers.threads[0].interrupted(), "control: the worker is clear once the task ended")
                assertEquals(false, victimSaw, "an unrelated coroutine met the interrupt status of a store call it never made; its file read: ${victimRead?.exceptionOrNull()}")
                assertTrue(victimRead?.isSuccess == true, "an unrelated coroutine's file read was refused: ${victimRead?.exceptionOrNull()}")
            }
        }
    }

    /**
     * The same through the public store on its default `Dispatchers.IO`: an app-key store whose AES-GCM provider is interrupted
     * (the round-9 scripted provider, PKCS#11 shape). Deterministic: the Unconfined caller resumes on exactly the worker whose slice is
     * still open, whichever worker that is.
     */
    @Test
    fun `an Unconfined caller's own file read right after an app-key get whose provider was interrupted is not refused`() {
        B9ScriptGcmSpi.causeOnRealTagFailure = true
        B9ScriptGcmSpi.arm()
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            check(Security.insertProviderAt(B9ScriptGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
            try {
                val store = JvmAppKeyEncryptedKeyValueStorage("breaker-r16", directory, AppEncryptionKey.Raw { ByteArray(32) { 7 } }, Dispatchers.IO, CrossProcessLock())
                var answer: StorageResult<String?>? = null
                var callerSaw: Boolean? = null
                var callerRead: Result<Int>? = null
                bounded {
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: an entry to read")
                    withContext(Dispatchers.Unconfined) {
                        B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                        answer = store.get("a")
                        callerSaw = Thread.currentThread().isInterrupted
                        callerRead = runCatching { readThroughChannel(probe) }
                        Thread.interrupted()
                    }
                }
                assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted (answer: $answer)")
                assertTrue(answer is StorageResult.Success || answer is StorageResult.Failure, "control: a typed answer: $answer")
                assertEquals(false, callerSaw, "the caller ran on with the interrupt status the store's boundary put back (answer: $answer); its file read: ${callerRead?.exceptionOrNull()}")
                assertTrue(callerRead?.isSuccess == true, "the caller's own file read right after the store call was refused: ${callerRead?.exceptionOrNull()}")
            } finally {
                Security.removeProvider(B9ScriptGcmProvider.NAME)
                B9ScriptGcmSpi.arm()
            }
        }
    }

    @Test
    fun `a store call on the caller's own dispatcher that resumes elsewhere hands the caller back a clear thread`() {
        Workers(2).use { workers: Workers ->
            var controlRestored = false
            var providerThread: Thread? = null
            var callerThread: Thread? = null
            var callerSaw: Boolean? = null
            bounded {
                withContext(workers.dispatcher) {
                    // Same dispatcher: kotlinx takes the undispatched path (UndispatchedCoroutine) into the store call.
                    withStoreContext(workers.dispatcher) {
                        yield()
                        providerThread = Thread.currentThread()
                        controlRestored = provoke()
                    }
                    callerThread = Thread.currentThread()
                    callerSaw = Thread.currentThread().isInterrupted
                }
            }
            assertTrue(controlRestored, "control: the boundary put the status back inside the store call")
            assertEquals(workers.threads[1].id, providerThread, "control: the call resumed on the second worker before the provider ran")
            assertEquals(workers.threads[1].id, callerThread, "control: the caller was resumed inline on that worker")
            assertEquals(false, callerSaw, "the caller resumed with the status the boundary put back (undispatched path)")
            assertFalse(workers.threads[0].interrupted(), "the first worker was left with a status")
            assertFalse(workers.threads[1].interrupted(), "the second worker was left with a status")
        }
    }

    /**
     * A seeded storm on a two-worker view of `Dispatchers.IO` (it runs queued tasks back to back, like the R15 shape): store calls
     * whose provider is interrupted, dispatched from `Dispatchers.Default` and undispatched from the view itself, some that suspend in
     * the middle, throw after the provider, or cancel themselves mid-slice, interleaved with unrelated coroutines on the view that
     * check their status at entry and read a file. No unrelated coroutine and no caller ever meets the status.
     */
    @Test
    fun `no unrelated coroutine on Dispatchers IO meets a stray status in a seeded storm of interrupted store calls`() {
        val seed: Int = SEED
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            val view: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
            val problems = ConcurrentLinkedQueue<String>()
            val restoredInside = AtomicInteger()
            val victims = AtomicInteger()

            suspend fun storeCall(index: Int, shape: Int) {
                try {
                    withStoreContext(view) {
                        if (provoke()) restoredInside.incrementAndGet() else problems.add("seed=$seed op=$index: control: the status was not put back")
                        when (shape) {
                            1 -> {
                                yield()
                                if (provoke()) restoredInside.incrementAndGet()
                            }
                            2 -> throw IllegalStateException("breaker R16: thrown after the provider")
                            3 -> {
                                cancel()
                                yield()
                            }
                        }
                    }
                } catch (thrown: IllegalStateException) {
                    if (thrown.message?.startsWith("breaker R16") != true) throw thrown
                }
            }

            bounded {
                coroutineScope {
                    repeat(OPS) { index: Int ->
                        val random = Random(seed * 1000 + index)
                        val shape: Int = random.nextInt(4)
                        when (random.nextInt(3)) {
                            0 -> launch(view) {
                                victims.incrementAndGet()
                                if (Thread.interrupted()) problems.add("seed=$seed op=$index: an unrelated coroutine started with the status set")
                                runCatching { readThroughChannel(probe) }.onFailure { error: Throwable ->
                                    problems.add("seed=$seed op=$index: an unrelated coroutine's file read was refused: $error")
                                    Thread.interrupted()
                                }
                            }
                            1 -> launch(Dispatchers.Default) {
                                launch { storeCall(index, shape) }.join()
                                if (Thread.currentThread().isInterrupted) problems.add("seed=$seed op=$index: the dispatched caller resumed interrupted")
                            }
                            else -> launch(view) {
                                launch { storeCall(index, shape) }.join()
                                if (Thread.interrupted()) problems.add("seed=$seed op=$index shape=$shape: the undispatched caller resumed interrupted")
                            }
                        }
                    }
                }
            }
            assertTrue(restoredInside.get() > OPS / 4, "seed=$seed control: interrupted store calls ran (${restoredInside.get()})")
            assertTrue(victims.get() > OPS / 6, "seed=$seed control: unrelated coroutines ran (${victims.get()})")
            assertEquals(emptyList(), problems.toList().take(10), "seed=$seed: ${problems.size} problems")
        }
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        const val OPS: Int = 600
        const val SEED: Int = 16
        val HANG_BOUND: Duration = 120.seconds
    }
}
