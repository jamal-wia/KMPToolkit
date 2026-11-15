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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Group sto-desktop, breaker round 17: attacks on the round-18 fix of the interrupt rule ([InterruptHygiene.endOfBlock] in
 * [withStoreContext], one per-slice state `ThreadLocal<Int>`). Contract under attack (05 "Interrupted provider", the `Jca` KDoc rule 1,
 * D-P12 of round 18): a store call never changes the interrupt status of a thread it runs on, per slice: a status set at slice entry is
 * never consumed, one that appeared without the boundary is never cleared, only a status the boundary itself put back is cleared, at the
 * end of the block (before kotlinx resumes an inline caller) and at the end of every slice; the caller's own code after the call does not
 * see it, on whichever thread it resumes.
 *
 * Defect classes and the production change that turns each test red (mutation by reading):
 * 1. a status that appears AFTER the end of the block, in caller code resumed inline inside the still-open slice, must survive the
 *    slice end: red if [InterruptHygiene.endOfBlock] cleared without consuming the note (the later `restoreThreadContext` would clear
 *    a status the boundary never put back);
 * 2. a nested store call on `Dispatchers.Unconfined` made by that inline caller, inside the open slice of the first call: red if the
 *    nested slice did not save and put back the enclosing state, or left its state in the thread-local (leak, with a control that the
 *    thread-local holds the note while it exists);
 * 3. `UnconfinedTestDispatcher` (never run before): red if the status reached the caller resumed inline;
 * 4. a store whose OWN dispatcher is `Dispatchers.Unconfined` (the block runs on the caller's thread, dispatched coroutine path):
 *    red if the caller's thread kept the status or the slice state;
 * 5. `runBlocking` with the store on runBlocking's own event loop (undispatched path on the caller's thread): red if the caller's
 *    thread kept the status (runBlocking would then cancel itself with `InterruptedException` at its next park);
 * 6. a seeded storm on `Dispatchers.IO.limitedParallelism(2)` mixing inline (Unconfined) callers, nested Unconfined store calls,
 *    throwing and self-cancelling calls, dispatched callers and unrelated victims that check the status AND the slice thread-local at
 *    entry: red on any stray status, and on any slice state left on a worker outside a slice (leak).
 */
class InterruptHygieneBreakerR17Test {

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

        fun <T> ask(question: () -> T): T {
            val answer = CompletableFuture<T>()
            post { answer.complete(question()) }
            return answer.get(HANG_SECONDS, TimeUnit.SECONDS)
        }

        fun stop() {
            stopped.set(true)
            LockSupport.unpark(thread)
            thread.join(HANG_SECONDS * 1000)
        }
    }

    /** A dispatcher over one raw worker. */
    private class OneWorker : AutoCloseable {
        val worker = Worker("breaker-r17")
        val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                worker.post(block)
            }
        }

        override fun close() {
            worker.stop()
        }
    }

    /** A random source whose draw is interrupted (a PKCS#11 token call, a blocking entropy read). */
    private class Interrupted : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            throw InterruptedException("breaker R17: the provider call was interrupted")
        }
    }

    /** One provider interrupt through the boundary; answers whether the boundary put the status back (the control). */
    private fun provoke(): Boolean {
        val failure: Throwable? = runCatching { Jca.randomBytes(Interrupted(), 8) }.exceptionOrNull()
        check(failure is JcaFailure && failure.cause is InterruptedException) { "control: the provider's interrupt is typed: $failure" }
        return Thread.currentThread().isInterrupted
    }

    /** The element's per-slice state on the current thread (null = no state: outside any slice, or a slice with nothing to remember). */
    private fun sliceState(): Any? = SLICE.get()

    /** An interruptible read (a `FileChannel`, like the stores' own I/O): refused with `ClosedByInterruptException` on an interrupted thread. */
    private fun readThroughChannel(file: File): Int =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel: FileChannel -> channel.read(ByteBuffer.allocate(64)) }

    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(HANG_BOUND) { block() } }

    private inline fun <T> inDirectory(block: (File) -> T): T {
        val directory: File = Files.createTempDirectory("kmptoolkit-breaker-r17").toFile()
        try {
            return block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private inline fun <T> withInterruptingGcm(block: () -> T): T {
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

    private fun appKeyStore(directory: File, dispatcher: CoroutineDispatcher): JvmAppKeyEncryptedKeyValueStorage =
        JvmAppKeyEncryptedKeyValueStorage("breaker-r17", directory, AppEncryptionKey.Raw { ByteArray(32) { 7 } }, dispatcher, CrossProcessLock())

    @Test
    fun `a status that appears in caller code resumed inline after a store call survives the end of the store's slice`() {
        OneWorker().use { one: OneWorker ->
            var controlRestored = false
            var callerThread: Thread? = null
            var callerSawAfterCall: Boolean? = null
            bounded {
                withContext(Dispatchers.Unconfined) {
                    withStoreContext(one.dispatcher) { controlRestored = provoke() }
                    callerThread = Thread.currentThread()
                    callerSawAfterCall = Thread.currentThread().isInterrupted
                    // A status that appears without the boundary, inside the still-open slice of the store call (the caller's own
                    // interrupt request; another thread interrupting the worker now is the same status).
                    Thread.currentThread().interrupt()
                }
            }
            val workerAfterTask: Boolean = one.worker.ask { Thread.interrupted() }
            assertTrue(controlRestored, "control: the boundary put the status back inside the store call")
            assertEquals(one.worker.id, callerThread, "control: the Unconfined caller resumed inline on the store's worker")
            assertEquals(false, callerSawAfterCall, "control: the end of the block cleared the status the boundary put back")
            assertTrue(
                workerAfterTask,
                "a status that appeared after the end of the block, without the boundary, was cleared when the store's slice ended " +
                    "(the note of the block was not consumed by endOfBlock)",
            )
        }
    }

    @Test
    fun `a nested Unconfined store call made inside the open slice of another one leaves the caller and the worker clear and no state`() {
        OneWorker().use { one: OneWorker ->
            var outerRestored = false
            var innerRestored = false
            var innerState: Any? = null
            var innerThread: Thread? = null
            var callerSaw: Boolean? = null
            var callerState: Any? = "unset"
            bounded {
                withContext(Dispatchers.Unconfined) {
                    withStoreContext(one.dispatcher) { outerRestored = provoke() }
                    // Inline on the worker, inside the outer call's slice. Same interceptor: the nested call takes the undispatched path.
                    withStoreContext(Dispatchers.Unconfined) {
                        innerThread = Thread.currentThread()
                        innerRestored = provoke()
                        innerState = sliceState()
                    }
                    callerSaw = Thread.currentThread().isInterrupted
                    callerState = sliceState()
                }
            }
            val workerInterrupted: Boolean = one.worker.ask { Thread.interrupted() }
            val workerState: Any? = one.worker.ask { sliceState() }
            assertTrue(outerRestored && innerRestored, "control: the boundary put the status back in both calls")
            assertEquals(one.worker.id, innerThread, "control: the nested call ran inline on the worker, inside the outer slice")
            assertNotNull(innerState, "control: the slice thread-local holds the note while the nested call is open (leak checks below are meaningful)")
            assertEquals(false, callerSaw, "the caller met the status of the nested store call after it returned")
            assertNull(callerState, "the nested call left its state in the slice of the outer call: $callerState")
            assertFalse(workerInterrupted, "the worker was left with a status after the outer task ended")
            assertNull(workerState, "slice state leaked on the worker after every slice ended: $workerState")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a caller on UnconfinedTestDispatcher reads its own file right after an app-key get whose provider was interrupted`() {
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            withInterruptingGcm {
                val store = appKeyStore(directory, Dispatchers.IO)
                var answer: StorageResult<String?>? = null
                var callerThread: Thread? = null
                var callerSaw: Boolean? = null
                var callerRead: Result<Int>? = null
                val testThread: Thread = Thread.currentThread()
                runTest(UnconfinedTestDispatcher(), timeout = HANG_BOUND) {
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: an entry to read")
                    B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                    answer = store.get("a")
                    callerThread = Thread.currentThread()
                    callerSaw = Thread.currentThread().isInterrupted
                    callerRead = runCatching { readThroughChannel(probe) }
                    Thread.interrupted()
                }
                assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted (answer: $answer)")
                assertTrue(answer is StorageResult.Failure, "control: a typed failure: $answer")
                assertNotEquals(testThread, callerThread, "control: the UnconfinedTestDispatcher caller resumed inline on the store's IO worker")
                assertEquals(false, callerSaw, "the UnconfinedTestDispatcher caller ran on with the status the boundary put back; its read: ${callerRead?.exceptionOrNull()}")
                assertTrue(callerRead?.isSuccess == true, "the caller's own file read right after the store call was refused: ${callerRead?.exceptionOrNull()}")
            }
        }
    }

    @Test
    fun `an app-key store on Dispatchers Unconfined whose provider was interrupted hands a Default caller back a clear thread`() {
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            withInterruptingGcm {
                val store = appKeyStore(directory, Dispatchers.Unconfined)
                var answer: StorageResult<String?>? = null
                var callerSaw: Boolean? = null
                var callerState: Any? = "unset"
                var callerRead: Result<Int>? = null
                bounded {
                    withContext(Dispatchers.Default) {
                        assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: an entry to read")
                        B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                        answer = store.get("a")
                        callerSaw = Thread.currentThread().isInterrupted
                        callerState = sliceState()
                        callerRead = runCatching { readThroughChannel(probe) }
                        Thread.interrupted()
                    }
                }
                assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted (answer: $answer)")
                assertTrue(answer is StorageResult.Failure, "control: a typed failure: $answer")
                assertEquals(false, callerSaw, "the Default caller kept the status that the boundary put back on its own thread; its read: ${callerRead?.exceptionOrNull()}")
                assertNull(callerState, "slice state left on the caller's thread: $callerState")
                assertTrue(callerRead?.isSuccess == true, "the caller's own file read was refused: ${callerRead?.exceptionOrNull()}")
            }
        }
    }

    @Test
    fun `runBlocking with the store on its own event loop keeps a clear thread after an interrupted provider`() {
        inDirectory { directory: File ->
            withInterruptingGcm {
                var answer: StorageResult<String?>? = null
                var callerThread: Thread? = null
                var callerSaw: Boolean? = null
                var hopped = false
                val testThread: Thread = Thread.currentThread()
                try {
                    runBlocking {
                        val loop = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
                        val store = appKeyStore(directory, loop)
                        assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: an entry to read")
                        B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                        answer = store.get("a")
                        callerThread = Thread.currentThread()
                        callerSaw = Thread.currentThread().isInterrupted
                        // runBlocking parks here; with a stray status it would cancel itself with InterruptedException.
                        withContext(Dispatchers.IO) { hopped = true }
                    }
                } finally {
                    Thread.interrupted()
                }
                assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted (answer: $answer)")
                assertTrue(answer is StorageResult.Failure, "control: a typed failure: $answer")
                assertEquals(testThread, callerThread, "control: the call ran on runBlocking's own thread")
                assertEquals(false, callerSaw, "runBlocking's thread kept the status the boundary put back after the store call returned")
                assertTrue(hopped, "runBlocking could not park after the store call")
            }
        }
    }

    /**
     * Seeded storm, seed in every message: 600 operations on a two-worker view of `Dispatchers.IO`. Shapes: an Unconfined caller of a
     * store call on the view (resumed inline inside the call's slice), the same followed by a nested Unconfined store call, store calls
     * that suspend mid-slice, throw or cancel themselves, dispatched callers from `Dispatchers.Default`, and unrelated victims on the view
     * that check the status and the slice thread-local at entry and read a file. No victim or caller meets a status or a slice state.
     */
    @Test
    fun `no inline caller and no unrelated coroutine meets a stray status or slice state in a seeded storm`() {
        val seed: Int = SEED
        inDirectory { directory: File ->
            val probe = File(directory, "probe.txt").apply { writeText("probe") }
            val view: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
            val problems = ConcurrentLinkedQueue<String>()
            val restoredInside = AtomicInteger()
            val inlineChecks = AtomicInteger()
            val victims = AtomicInteger()
            val stateSeenInside = AtomicInteger()

            suspend fun storeCall(index: Int, shape: Int) {
                try {
                    withStoreContext(view) {
                        if (provoke()) restoredInside.incrementAndGet() else problems.add("seed=$seed op=$index: control: the status was not put back")
                        if (sliceState() != null) stateSeenInside.incrementAndGet()
                        when (shape) {
                            1 -> {
                                yield()
                                if (provoke()) restoredInside.incrementAndGet()
                            }
                            2 -> throw IllegalStateException("breaker R17: thrown after the provider")
                            3 -> {
                                cancel()
                                yield()
                            }
                        }
                    }
                } catch (thrown: IllegalStateException) {
                    if (thrown.message?.startsWith("breaker R17") != true) throw thrown
                } catch (_: CancellationException) {
                    // shape 3: the store call's own scope was cancelled; the caller goes on.
                }
            }

            fun checkInline(index: Int, what: String) {
                inlineChecks.incrementAndGet()
                if (Thread.currentThread().isInterrupted) problems.add("seed=$seed op=$index: $what resumed with the status set")
                sliceState()?.let { state: Any -> problems.add("seed=$seed op=$index: $what met slice state $state after the call") }
                runCatching { readThroughChannel(probe) }.onFailure { error: Throwable ->
                    problems.add("seed=$seed op=$index: $what could not read its own file: $error")
                }
                Thread.interrupted()
            }

            bounded {
                coroutineScope {
                    repeat(OPS) { index: Int ->
                        val random = Random(seed * 1000 + index)
                        val shape: Int = random.nextInt(4)
                        when (random.nextInt(4)) {
                            0 -> launch(view) {
                                victims.incrementAndGet()
                                if (Thread.interrupted()) problems.add("seed=$seed op=$index: an unrelated coroutine started with the status set")
                                sliceState()?.let { state: Any -> problems.add("seed=$seed op=$index: an unrelated coroutine met leaked slice state $state") }
                                runCatching { readThroughChannel(probe) }.onFailure { error: Throwable ->
                                    problems.add("seed=$seed op=$index: an unrelated coroutine's file read was refused: $error")
                                    Thread.interrupted()
                                }
                            }
                            1 -> launch(Dispatchers.Unconfined) {
                                storeCall(index, shape)
                                checkInline(index, "an Unconfined caller (shape $shape)")
                            }
                            2 -> launch(Dispatchers.Unconfined) {
                                storeCall(index, shape)
                                withStoreContext(Dispatchers.Unconfined) {
                                    if (provoke()) restoredInside.incrementAndGet()
                                }
                                checkInline(index, "an Unconfined caller after a nested Unconfined call (shape $shape)")
                            }
                            else -> launch(Dispatchers.Default) {
                                storeCall(index, shape)
                                if (Thread.currentThread().isInterrupted) problems.add("seed=$seed op=$index: the dispatched caller resumed interrupted")
                            }
                        }
                    }
                }
            }
            assertTrue(restoredInside.get() > OPS / 2, "seed=$seed control: interrupted store calls ran (${restoredInside.get()})")
            assertTrue(stateSeenInside.get() > OPS / 4, "seed=$seed control: the slice thread-local held state inside calls (${stateSeenInside.get()})")
            assertTrue(inlineChecks.get() > OPS / 4, "seed=$seed control: inline callers were checked (${inlineChecks.get()})")
            assertTrue(victims.get() > OPS / 8, "seed=$seed control: unrelated coroutines ran (${victims.get()})")
            assertEquals(emptyList(), problems.toList().take(10), "seed=$seed: ${problems.size} problems")
        }
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        const val OPS: Int = 600
        const val SEED: Int = 17
        val HANG_BOUND: Duration = 120.seconds

        /** The element's private per-slice thread-local, read for the leak checks (control assertions prove it is the live one). */
        val SLICE: ThreadLocal<*> = InterruptHygiene::class.java.getDeclaredField("slice").apply { isAccessible = true }.get(InterruptHygiene) as ThreadLocal<*>
    }
}
