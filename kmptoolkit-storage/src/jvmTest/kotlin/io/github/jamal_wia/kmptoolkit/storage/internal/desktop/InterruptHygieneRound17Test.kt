package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Group sto-desktop, round 17 (breaker round 15, finding m1): a store call never leaves an interrupt status on a thread that it
 * found clear ([InterruptHygiene]; the contract was confirmed by an Opus advisor, see the round-17 report).
 *
 * The workers here are raw threads that, unlike `ThreadPoolExecutor`, never clear the interrupt status between two tasks (the way
 * kotlinx.coroutines' `Dispatchers.IO` behaves: it clears only when a worker parks). Each test asks a worker for its status with a
 * task of its own after the store call ended, so a status left behind is seen as it would reach the next coroutine. The interrupted
 * provider is the boundary's own path: a random source that throws `InterruptedException` (`Jca.randomBytes`), or the scripted AES-GCM
 * provider of the round-9 breaker for the store-level tests.
 *
 * Mutations by reading (each turns a named test red): no `noteRestored()` in `Jca` or an element that clears nothing (the
 * boundary tests); an element that clears every status (the two preserved-status tests); a block-end clear in place of a per-slice
 * one (the resume-on-another-thread test); a nested slice that drops the outer note (the nested tests); a `withContext` of a
 * dispatcher that bypasses `withStoreContext` (the source scan).
 */
class InterruptHygieneRound17Test {

    /** One raw worker thread: runs queued tasks back to back and never touches the interrupt status of its own accord. */
    private class Worker(name: String) {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val stopped = AtomicBoolean()
        private val thread: Thread = Thread {
            while (!stopped.get()) {
                val next: Runnable? = tasks.poll()
                // Park returns at once while the status is set (it does not clear it), so a stray status makes this loop spin until
                // the next task arrives: a failing run, never a passing one, and the task that asks for the status arrives at once.
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

        /** The worker's own view of its interrupt status, read without changing it. */
        fun interrupted(): Boolean {
            val answer = CompletableFuture<Boolean>()
            post { answer.complete(Thread.currentThread().isInterrupted) }
            return answer.get(HANG_SECONDS, TimeUnit.SECONDS)
        }

        /** Puts the worker back to a clear status. */
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

    /**
     * A dispatcher over [count] raw workers: the n-th dispatch goes to worker `min(n, count - 1)`, so a call that suspends once
     * resumes on the second worker. [preInterrupt] sets the status of the thread right before each dispatched block runs, the way
     * an interrupted caller thread would reach a call (`Dispatchers.Unconfined`, or a call that runs inline on that thread).
     */
    private class Workers(count: Int, private val preInterrupt: Boolean = false) : AutoCloseable {
        val threads: List<Worker> = List(count) { index: Int -> Worker("hygiene-$index") }
        private val dispatches = AtomicInteger()

        val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                val chosen: Worker = threads[minOf(dispatches.getAndIncrement(), threads.size - 1)]
                chosen.post {
                    if (preInterrupt) Thread.currentThread().interrupt()
                    block.run()
                }
            }
        }

        override fun close() {
            threads.forEach { it.stop() }
        }
    }

    /** A random source whose draw is interrupted, like a PKCS#11 token call or a blocking entropy read. */
    private class Interrupted : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            throw InterruptedException("the provider call was interrupted")
        }
    }

    /** A provider interrupt through the boundary: typed, and the status is put back on the calling thread (control assertions). */
    private fun provokeInterruptThroughBoundary() {
        val failure: JcaFailure = assertFailsWith<JcaFailure> { Jca.randomBytes(Interrupted(), 8) }
        assertIs<InterruptedException>(failure.cause, "control: the provider's interrupt is the cause")
        assertTrue(Thread.currentThread().isInterrupted, "control: the boundary put the status back for the work that follows")
    }

    private fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(HANG_BOUND) { block() } }

    @Test
    fun `the status that the boundary put back is cleared when the slice ends, on the thread of the provider and after a resume elsewhere`() {
        Workers(2).use { workers: Workers ->
            var first: Thread? = null
            var second: Thread? = null
            bounded {
                withStoreContext(workers.dispatcher) {
                    first = Thread.currentThread()
                    provokeInterruptThroughBoundary()
                    // The call suspends and resumes on the other worker: the first worker's slice ends here.
                    yield()
                    second = Thread.currentThread()
                    assertFalse(Thread.currentThread().isInterrupted, "the resumed slice never had a status")
                }
            }
            assertEquals(workers.threads[0].id, first, "control: the provider ran on the first worker")
            assertEquals(workers.threads[1].id, second, "control: the call resumed on the second worker")
            assertFalse(workers.threads[0].interrupted(), "the first worker kept the status the boundary put back (it reaches the next task there)")
            assertFalse(workers.threads[1].interrupted(), "the second worker was left with a status")
        }
    }

    @Test
    fun `a status that was set when the call reached the thread is left set`() {
        Workers(1, preInterrupt = true).use { workers: Workers ->
            bounded {
                withStoreContext(workers.dispatcher) {
                    assertTrue(Thread.currentThread().isInterrupted, "control: the caller's interrupt reached the slice")
                    provokeInterruptThroughBoundary()
                }
            }
            assertTrue(workers.threads[0].interrupted(), "the interrupt that the caller's thread already had was consumed")
            workers.threads[0].clear()
        }
    }

    @Test
    fun `a status that appeared without the boundary is never cleared`() {
        Workers(1).use { workers: Workers ->
            bounded {
                withStoreContext(workers.dispatcher) {
                    assertFalse(Thread.currentThread().isInterrupted, "control: the slice started clear")
                    // Another thread interrupted this one: no provider is involved.
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(workers.threads[0].interrupted(), "an interrupt that no provider consumed was cleared")
            workers.threads[0].clear()
        }
    }

    @Test
    fun `a nested store call keeps the note of the outer slice and the outer slice clears it`() {
        for (outerProvokes: Boolean in listOf(true, false)) {
            Workers(1).use { workers: Workers ->
                bounded {
                    withStoreContext(workers.dispatcher) {
                        if (outerProvokes) provokeInterruptThroughBoundary()
                        withStoreContext(workers.dispatcher) {
                            if (outerProvokes) assertTrue(Thread.currentThread().isInterrupted, "control: the inner call runs on the interrupted thread")
                            else provokeInterruptThroughBoundary()
                        }
                        // The inner slice found the thread clear in the second variant and clears what its boundary put back; in the first it
                        // found the thread interrupted and must leave it for the outer slice, which owns the note.
                        assertEquals(outerProvokes, Thread.currentThread().isInterrupted, "outerProvokes=$outerProvokes: the status between the two slices")
                    }
                }
                assertFalse(workers.threads[0].interrupted(), "outerProvokes=$outerProvokes: the outer slice left the status the boundary put back")
            }
        }
    }

    @Test
    fun `a call cancelled while it waits still leaves its thread clear`() {
        Workers(1).use { workers: Workers ->
            bounded {
                val waiting = CompletableDeferred<Unit>()
                val call = launch {
                    withStoreContext(workers.dispatcher) {
                        try {
                            waiting.complete(Unit)
                            awaitCancellation()
                        } finally {
                            // The cleanup of a cancelled call that meets an interrupted provider (a release that draws or hashes).
                            runCatching { provokeInterruptThroughBoundary() }
                        }
                    }
                }
                waiting.await()
                call.cancelAndJoin()
            }
            assertFalse(workers.threads[0].interrupted(), "a cancelled call left the status on its thread")
        }
    }

    @Test
    fun `a direct call of the boundary on its own thread still puts the status back`() {
        Workers(1).use { workers: Workers ->
            val done = CompletableFuture<Boolean>()
            workers.threads[0].post {
                try {
                    runCatching { Jca.randomBytes(Interrupted(), 8) }
                    done.complete(Thread.currentThread().isInterrupted)
                } catch (error: Throwable) {
                    done.completeExceptionally(error)
                }
            }
            assertTrue(done.get(HANG_SECONDS, TimeUnit.SECONDS), "the boundary no longer restores the status for a direct caller")
            workers.threads[0].clear()
        }
    }

    private fun causes(error: Throwable?): List<Throwable> = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

    /**
     * The R15 shape on a worker that clears nothing between tasks, through a real store and the scripted provider that is
     * interrupted at the key check: the call answers typed and the worker is left clear.
     */
    @Test
    fun `an OS-key store call whose provider was interrupted leaves its worker clear`() {
        B9ScriptGcmSpi.causeOnRealTagFailure = true
        B9ScriptGcmSpi.arm()
        Workers(1).use { workers: Workers ->
            SecureTestRig(DesktopOs.MACOS, dispatcher = workers.dispatcher).use { rig: SecureTestRig ->
                check(Security.insertProviderAt(B9ScriptGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
                try {
                    bounded {
                        val store: EncryptedKeyValueStorage = rig.store()
                        assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: a warm store with an entry")
                        assertFalse(workers.threads[0].interrupted(), "control: the worker is clear before the interrupted call")
                        B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                        val answer: StorageResult<String?> = store.get("k")
                        assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted")
                        B9ScriptGcmSpi.arm()
                        assertTrue(answer is StorageResult.Success || answer is StorageResult.Failure, "typed answer: $answer")
                    }
                    assertFalse(workers.threads[0].interrupted(), "the store call left the interrupt status on its worker")
                } finally {
                    Security.removeProvider(B9ScriptGcmProvider.NAME)
                    B9ScriptGcmSpi.arm()
                }
            }
        }
    }

    /** `changeKey` is a store dispatch of its own: the same rule, with the interrupt at the first decryption of the re-key. */
    @Test
    fun `a re-key whose provider was interrupted leaves its worker clear`() {
        B9ScriptGcmSpi.causeOnRealTagFailure = true
        B9ScriptGcmSpi.arm()
        val directory: File = java.nio.file.Files.createTempDirectory("kmptoolkit-hygiene").toFile()
        Workers(1).use { workers: Workers ->
            check(Security.insertProviderAt(B9ScriptGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
            try {
                val oldKey = AppEncryptionKey.Raw { ByteArray(32) { 3 } }
                val newKey = AppEncryptionKey.Raw { ByteArray(32) { 4 } }
                val store = JvmAppKeyEncryptedKeyValueStorage("hygiene", directory, oldKey, workers.dispatcher, CrossProcessLock())
                bounded {
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: an entry to re-encrypt")
                    assertFalse(workers.threads[0].interrupted(), "control: the worker is clear before the interrupted call")
                    B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                    val answer: StorageResult<Unit> = store.changeKey(newKey)
                    assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted")
                    B9ScriptGcmSpi.arm()
                    if (answer is StorageResult.Failure) assertIs<StorageError.OperationFailed>(answer.error, "typed failure: $answer")
                }
                assertFalse(workers.threads[0].interrupted(), "the re-key left the interrupt status on its worker")
            } finally {
                Security.removeProvider(B9ScriptGcmProvider.NAME)
                B9ScriptGcmSpi.arm()
                directory.deleteRecursively()
            }
        }
    }

    /** A caller whose thread is already interrupted keeps its interrupt, and the call answers as documented (05, "A call on an interrupted thread"). */
    @Test
    fun `a store call that reaches an interrupted thread answers OperationFailed and leaves the status set`() {
        Workers(1, preInterrupt = true).use { workers: Workers ->
            SecureTestRig(DesktopOs.MACOS, dispatcher = workers.dispatcher).use { rig: SecureTestRig ->
                val answer: StorageResult<Unit> = bounded { rig.store().put("k", "v") }
                val failure: StorageResult.Failure = assertIs<StorageResult.Failure>(answer, "an interrupted thread: $answer")
                val error: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(failure.error, "typed: ${failure.error}")
                assertTrue(causes(error.cause).any { it is java.nio.channels.ClosedByInterruptException }, "the JDK's refusal is the cause: $error")
                assertTrue(workers.threads[0].interrupted(), "the caller's interrupt was consumed by the store call")
                workers.threads[0].clear()
            }
        }
    }

    /**
     * Every dispatch of a desktop store goes through `withStoreContext`: a `withContext` of a dispatcher anywhere else in the jvm
     * sources would skip the element and leave the status behind. Lexical, like `JcaBoundaryScanTest`, and the module's source
     * directory must be found (the test fails otherwise).
     */
    @Test
    fun `no jvm source dispatches a store with a plain withContext`() {
        val working = File(System.getProperty("user.dir") ?: "")
        val src: File = listOf(File(working, "src"), File(File(working, "kmptoolkit-storage"), "src"))
            .firstOrNull { File(File(it, "jvmMain"), "kotlin").isDirectory }
            ?: error("the storage module's jvmMain sources were not found from ${working.path}: the scan must not pass silently")
        val files: List<File> = src.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.lowercase().startsWith("jvm") && !it.name.lowercase().endsWith("test") }
            .flatMap { root: File -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 20, "control: the scan sees the module's sources (${files.size})")
        val viaHelper: Int = files.sumOf { file: File -> file.readLines().count { "withStoreContext(" in it } }
        assertTrue(viaHelper >= 6, "control: the stores dispatch through withStoreContext ($viaHelper sites)")
        val offenders: List<String> = files.flatMap { file: File ->
            file.readLines().withIndex().filter { (_, line: String) ->
                val code: String = line.trim()
                !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") && WITH_CONTEXT.containsMatchIn(code)
            }.map { (index: Int, line: String) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }.filterNot { it.startsWith("InterruptHygiene.kt:") }
        assertEquals(emptyList(), offenders, "a store dispatch that skips InterruptHygiene")
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        const val MAX_CAUSE_DEPTH: Int = 12
        val HANG_BOUND: Duration = 120.seconds
        val WITH_CONTEXT: Regex = Regex("""\bwithContext\(\s*\w*[dD]ispatcher""")
    }
}
