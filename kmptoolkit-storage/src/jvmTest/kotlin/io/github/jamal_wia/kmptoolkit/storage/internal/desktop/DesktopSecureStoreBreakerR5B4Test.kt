package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 4 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-4), against task/sto-desktop 9dadc3e4:
 * the round-6 phase A changes as they reach the encrypted stores, through the store API only, and the round-6 docs claims.
 *
 * - A-m3 / D-P4 (a native thread that dies hands its work to a replacement): the store answers typed, never "key lost", rolls
 *   a create back, keeps working; and a key the dying thread had fetched is still wiped (the executor's KDoc: a result
 *   nobody reads goes to `discard`, "to wipe a key").
 * - A-m1 (a closed executor makes no thread): every store call answers typed with no half state.
 * - A-M1 / D-P11 (a dangling symbolic link at the lock path): every operation of both store types, `changeKey` included,
 *   answers `OperationFailed` at once (a lock deadline of one hour turns a poll-out into a hang-bound failure), never creates
 *   the link's target, leaves the store byte-identical, and releases the claim.
 * - Lock and store paths that are not regular files (a named pipe): every store call answers within its budget (05:
 *   "under one 10 s budget"); opening a pipe for writing blocks until a reader appears, which no budget can interrupt.
 * - O7 docs (05 "A call on an interrupted thread is not served"): what really happens with the store's default dispatcher,
 *   and the cause the sentence names.
 *
 * Fake binding only, real files in a fresh directory per test, hang bounds only.
 */
class DesktopSecureStoreBreakerR5B4Test {

    private fun <T> withRig(
        os: DesktopOs = DesktopOs.MACOS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend SecureTestRig.() -> T,
    ): T = SecureTestRig(os, dispatcher = dispatcher).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    private fun SecureTestRig.temps(): List<String> = fileNames().filter { !it.endsWith(".bin") && !it.endsWith(".lock") }

    /** A binding that delegates to [fake] and keeps every key array the macOS copy call hands out. */
    private class KeyRecordingBinding(private val fake: FakeNativeKeyStoreBinding) : NativeKeyStoreBinding {
        val handedOut: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        override fun macOs(): MacKeychainCalls = object : MacKeychainCalls by fake.macOs() {
            override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
                val raw: RawResult<ByteArray?> = fake.macOs().copyGenericPassword(service, account, allowUi)
                if (raw is RawResult.Success) raw.value?.let { handedOut += it }
                return raw
            }
        }

        override fun windows(): DpapiCalls = fake.windows()

        override fun linux(): SecretServiceCalls = fake.linux()
    }

    // ---- a native thread that dies ----

    /**
     * A cold-cache `get` fetches the key on the native thread; the thread dies while delivering it (an `OutOfMemoryError`
     * where the executor allocates the outcome: the `beforeDelivery` seam). The caller gets a typed `OperationFailed`, never
     * "key lost" (no quarantine, file byte-identical), the replacement thread serves the next call, AND the key bytes the
     * binding handed out are wiped: nobody received them, which is exactly the case the executor hands to `discard`
     * ("its late result is discarded and the key bytes in it are wiped", 05 and NativeExecutor KDoc). Red on: the fetched key
     * dropped without its discard handler (a 32-byte data key left on the heap).
     */
    @Test
    fun `a key fetched by a native thread that dies while delivering it is wiped and never read as key lost`() = withRig {
        assertEquals(StorageResult.Success(Unit), store().put("a", "1"), "control")
        val before: ByteArray = dataFile().readBytes()
        val binding = KeyRecordingBinding(fake)
        val armed = AtomicBoolean(false)
        NativeExecutor(beforeDelivery = { if (armed.compareAndSet(true, false)) throw OutOfMemoryError("breaker: delivery") }).use { executor: NativeExecutor ->
            val source = FixedBindingSource(BindingSelection.Selected(binding, DesktopOs.MACOS, BindingKind.FAKE))
            armed.set(true)
            val failed = assertIs<StorageError.OperationFailed>(failure(store(source = source, executor = executor, cache = OsKeyCache()).get("a")))
            assertEquals(StorageOperation.GET, failed.operation)
            assertFalse(armed.get(), "control: the seam fired")
            assertContentEquals(before, dataFile().readBytes(), "the user's file changed after a native failure")
            assertEquals(emptyList(), quarantined(), "a native failure was read as key lost")

            val handed: ByteArray = assertNotNull(binding.handedOut.singleOrNull(), "control: the binding handed out exactly one key: ${binding.handedOut.size}")
            assertTrue(handed.all { it == 0.toByte() }, "the key fetched by the dying thread was never wiped (nobody received it)")

            assertEquals(StorageResult.Success("1"), store(source = source, executor = executor, cache = OsKeyCache()).get("a"), "the replacement thread serves the next call")
        }
    }

    /**
     * The first put of a macOS store: the native thread dies while delivering the outcome of `addGenericPassword` (the item
     * WAS added). The put is a typed `OperationFailed(PUT, k)`, the create is rolled back (no store file), and the next put
     * works with exactly one item (the orphan item is replaced by the delete-then-add of install) and reads back from a new
     * cache. Red on: the Error escaping `put`, a half-created store, two items, or a store that cannot be created again.
     */
    @Test
    fun `a create whose native thread dies while adding the item rolls back and the next put works`() = withRig {
        val armed = AtomicBoolean(false)
        fake.before = { name: String -> if (name == "addGenericPassword") armed.compareAndSet(false, true) }
        val fired = AtomicBoolean(false)
        NativeExecutor(beforeDelivery = { if (armed.compareAndSet(true, false) && fired.compareAndSet(false, true)) throw OutOfMemoryError("breaker: add") }).use { executor: NativeExecutor ->
            val failed = assertIs<StorageError.OperationFailed>(failure(store(executor = executor).put("k", "v")))
            assertEquals(StorageOperation.PUT, failed.operation)
            assertEquals("k", failed.key)
            assertTrue(fired.get(), "control: the native thread died in the add")
            assertFalse(dataFile().exists(), "the create was not rolled back: ${fileNames()}")

            assertEquals(StorageResult.Success(Unit), store(executor = executor).put("k", "v2"), "the next put")
            assertEquals(1, fake.macItems.size, "exactly one item")
            assertEquals(StorageResult.Success("v2"), store(executor = executor, cache = OsKeyCache()).get("k"))
            assertEquals(0, executor.pendingCalls, "nothing left queued or running")
        }
    }

    /**
     * A store whose executor is closed (A-m1: no thread is ever made again): a first put, `ensureKey`, and a cold-cache get and
     * put on an existing store answer typed (`OperationFailed` of the right operation), never throw, create nothing, never
     * quarantine, on every OS; a store with a working executor then serves the same directory. Red on: a thread made, a thrown
     * error, a half-created store, or a closed executor read as key lost.
     */
    @Test
    fun `every call of a store whose executor is closed answers typed and leaves no half state, on every OS`() {
        for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
            withRig(os) {
                val options = DesktopSecureStorageOptions(allowUserInteraction = false)
                val closed = NativeExecutor(threadFactory = { runnable: Runnable -> error("$os: a closed executor made a thread for $runnable") })
                closed.close()
                val fresh = store(executor = closed, options = options)
                assertEquals(StorageOperation.PUT, assertIs<StorageError.OperationFailed>(failure(fresh.put("k", "v")), "$os put").operation)
                assertEquals(StorageOperation.ENSURE_KEY, assertIs<StorageError.OperationFailed>(failure(fresh.ensureKey()), "$os ensureKey").operation)
                assertFalse(dataFile().exists(), "$os: a store file was left: ${fileNames()}")
                assertEquals(emptyList(), temps(), "$os: a temporary file was left")

                assertEquals(StorageResult.Success(Unit), store(options = options).put("a", "1"), "$os: control with a working executor")
                val before: ByteArray = dataFile().readBytes()
                val cold = store(executor = closed, options = options, cache = OsKeyCache())
                assertEquals(StorageOperation.GET, assertIs<StorageError.OperationFailed>(failure(cold.get("a")), "$os get").operation)
                assertEquals(StorageOperation.PUT, assertIs<StorageError.OperationFailed>(failure(cold.put("b", "2")), "$os put").operation)
                assertContentEquals(before, dataFile().readBytes(), "$os: the user's file changed")
                assertEquals(emptyList(), quarantined(), "$os: a closed executor was read as key lost")
                assertEquals(StorageResult.Success("1"), store(options = options, cache = OsKeyCache()).get("a"), "$os: a working executor reads the data")
            }
        }
    }

    // ---- lock path: dangling symbolic link ----

    /**
     * The lock path is a symbolic link whose target does not exist (and, second case, a link to itself): every operation of the
     * OS-key store and of the app-key store, `changeKey` included, on a fresh store and on one with data, answers
     * `OperationFailed` of its own operation at once. The lock's deadline is ONE HOUR, so a call that polls instead of failing
     * at once runs into the hang bound. The target is never created, the link stays, the data file is byte-identical, no
     * temporary file is left, and once the link is removed the same instances work (no claim kept).
     */
    @Test
    fun `a dangling or looping symbolic link at the lock path fails every store operation at once and touches nothing`() {
        TestHost.assumePosixFileSemantics("a symbolic link without privileges; Windows needs the create-symlink right")
        for (loop: Boolean in listOf(false, true)) {
            withRig {
                val longLock = CrossProcessLock(deadline = 1.hours)
                val key = AppEncryptionKey.Raw { ByteArray(32) { 7 } }
                val stores: List<Triple<String, EncryptedKeyValueStorage, File>> = listOf(
                    Triple("OS-key", store(lock = longLock), dataFile()),
                    Triple("app-key", JvmAppKeyEncryptedKeyValueStorage("app", directory, key, Dispatchers.IO, longLock), File(directory, "${appKeyStoreId("app")}.bin")),
                )
                for (withData: Boolean in listOf(false, true)) {
                    for ((label: String, store: EncryptedKeyValueStorage, data: File) in stores) {
                        val case = "$label loop=$loop data=$withData"
                        if (withData) assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "$case: control")
                        val lock: File = CrossProcessLock.lockFileOf(data)
                        lock.delete()
                        val target = File(directory, "missing-target-$label")
                        Files.createSymbolicLink(lock.toPath(), if (loop) lock.toPath() else target.toPath())
                        val before: ByteArray? = data.takeIf { it.exists() }?.readBytes()
                        val calls: Int = fake.calls.size

                        val operations: MutableList<Pair<StorageOperation, suspend () -> StorageResult<*>>> = mutableListOf(
                            StorageOperation.PUT to { store.put("b", "2") },
                            StorageOperation.GET to { store.get("a") },
                            StorageOperation.REMOVE to { store.remove("a") },
                            StorageOperation.CLEAR to { store.clear() },
                            StorageOperation.ENSURE_KEY to { store.ensureKey() },
                        )
                        if (store is JvmAppKeyEncryptedKeyValueStorage) operations += StorageOperation.CHANGE_KEY to { store.changeKey(AppEncryptionKey.Raw { ByteArray(32) { 8 } }) }
                        for ((operation: StorageOperation, call: suspend () -> StorageResult<*>) in operations) {
                            val failed = assertIs<StorageError.OperationFailed>(failure(call()), "$case $operation")
                            assertEquals(operation, failed.operation, "$case $operation")
                        }
                        assertTrue(Files.isSymbolicLink(lock.toPath()), "$case: the link was replaced")
                        assertFalse(target.exists(), "$case: the link's target was created")
                        assertEquals(before?.toList(), data.takeIf { it.exists() }?.readBytes()?.toList(), "$case: the store file changed without its lock")
                        assertEquals(emptyList(), temps(), "$case: a temporary file was left")
                        assertEquals(calls, fake.calls.size, "$case: a key-store call was made without the lock")

                        assertTrue(lock.delete(), "control: the link existed")
                        assertEquals(StorageResult.Success(Unit), store.put("c", "3"), "$case: the store works once the link is gone")
                        assertTrue(store.clear() is StorageResult.Success, "$case: reset")
                    }
                }
            }
        }
    }

    // ---- lock and store paths that are named pipes ----

    private fun fifoOrSkip(file: File) {
        TestHost.assumePosixFileSemantics("a named pipe needs mkfifo")
        val process: Process = ProcessBuilder("mkfifo", file.path).redirectErrorStream(true).start()
        val finished: Boolean = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        org.junit.Assume.assumeTrue("mkfifo is not available here", finished && process.exitValue() == 0 && file.exists())
    }

    /**
     * Runs [call] on its own thread and waits at most [CALL_BOUND_MS] (three times the 10 s lock budget; a hang bound, not a
     * timing assertion). A call still running then is freed by opening every named pipe of [pipes] read-write (which never
     * blocks and completes a writer's open), and the hang is reported as the failure.
     */
    private fun answersWithinBudget(label: String, pipes: List<File>, call: suspend () -> StorageResult<*>): StorageResult<*> {
        val answer = AtomicReference<Result<StorageResult<*>>>()
        val worker = Thread({ answer.set(runCatching { runBlocking { call() } }) }, "breaker-b4-$label")
        worker.isDaemon = true
        worker.start()
        worker.join(CALL_BOUND_MS)
        val hung: Boolean = worker.isAlive
        // The store's own dispatcher thread is the one that blocks: report the frames of the thread inside CrossProcessLock.
        val stack: String = if (!hung) "" else Thread.getAllStackTraces().values
            .firstOrNull { frames: Array<StackTraceElement> -> frames.any { "CrossProcessLock" in it.className } }
            ?.take(STACK_FRAMES)?.joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "no thread in CrossProcessLock"
        if (hung) {
            for (pipe: File in pipes) runCatching { RandomAccessFile(pipe, "rw").use { worker.join(CALL_BOUND_MS) } }
        }
        assertFalse(hung, "$label: the store call did not answer within ${CALL_BOUND_MS / 1000} s (3x its 10 s budget); blocked at: $stack")
        return answer.get().getOrThrow()
    }

    /**
     * The lock path of an encrypted store is a named pipe (a leftover of a tool, or planted by another program of the user):
     * a first put and, on a store with data, a get must answer typed within the budget the docs state ("under one 10 s
     * budget ... a call that cannot get them answers"), like a lock path that is a directory. The files layer already refuses
     * to open a pipe for the store file ("Opening a pipe or a device could block forever"); `CrossProcessLock` opens the lock
     * path with `FileChannel.open(WRITE)`, which on a pipe blocks in `open(2)` until a reader appears, beyond any timeout or
     * cancellation, while the process-wide in-process lock of that store is held. Red on: the call hangs.
     */
    @Test
    fun `a named pipe at the lock path fails the store call within its budget instead of blocking forever`() = withRig {
        assertEquals(StorageResult.Success(Unit), store().put("a", "1"), "control")
        val before: ByteArray = dataFile().readBytes()
        val lock: File = CrossProcessLock.lockFileOf(dataFile())
        assertTrue(lock.delete(), "control: the lock file existed")
        fifoOrSkip(lock)

        val get: StorageResult<*> = withContext(Dispatchers.IO) { answersWithinBudget("get", listOf(lock)) { store(cache = OsKeyCache()).get("a") } }
        assertIs<StorageError.OperationFailed>(failure(get), "get")
        val put: StorageResult<*> = withContext(Dispatchers.IO) { answersWithinBudget("put", listOf(lock)) { store().put("b", "2") } }
        assertIs<StorageError.OperationFailed>(failure(put), "put")
        assertContentEquals(before, dataFile().readBytes(), "the store file changed without its lock")
    }

    /**
     * Control for the test above, and the same attack one level up: the STORE file is a named pipe. The files layer refuses
     * it without opening it, so every operation answers `OperationFailed` within the budget and nothing is quarantined or
     * replaced (a pipe is not "unparsable data").
     */
    @Test
    fun `a named pipe at the store path answers OperationFailed for every operation and is never replaced`() = withRig {
        fifoOrSkip(dataFile())
        val store = store()
        for ((operation: StorageOperation, call: suspend () -> StorageResult<*>) in listOf<Pair<StorageOperation, suspend () -> StorageResult<*>>>(
            StorageOperation.GET to { store.get("a") },
            StorageOperation.PUT to { store.put("a", "1") },
            StorageOperation.REMOVE to { store.remove("a") },
            StorageOperation.CLEAR to { store.clear() },
            StorageOperation.ENSURE_KEY to { store.ensureKey() },
        )) {
            val result: StorageResult<*> = withContext(Dispatchers.IO) { answersWithinBudget("$operation", listOf(dataFile())) { call() } }
            assertEquals(operation, assertIs<StorageError.OperationFailed>(failure(result), "$operation").operation)
        }
        assertFalse(dataFile().isFile, "the pipe was replaced by a store file")
        assertEquals(emptyList(), quarantined(), "a pipe was moved to quarantine")
        assertEquals(emptyMap(), fake.macItems.toMap(), "an item was created for a store path that is a pipe")
    }

    // ---- O7: calls on an interrupted thread ----

    /**
     * 05 says: "A store call made on a thread whose interrupt status is already set ... does not do its work: ... every
     * operation of an encrypted store, a `get` included, answers `OperationFailed` with a `ClosedByInterruptException` as its
     * cause". That holds only when the store's dispatcher runs the call on the caller's own thread (`Unconfined`, as in the
     * round-3 breaker test). With the DEFAULT dispatcher (`Dispatchers.IO`) the file I/O runs on a pool thread whose status is
     * clear, so the call IS served. This test pins what really happens on both dispatchers (green), so the sentence can be
     * corrected against it: default dispatcher, served and the status kept; `Unconfined`, `OperationFailed` and the status
     * kept, with the exact cause recorded in the message for the docs.
     */
    @Test
    fun `an interrupted caller is served with the default dispatcher and refused only when the store runs on its thread`() {
        withRig {
            val caller = Executors.newSingleThreadExecutor { runnable: Runnable -> Thread(runnable, "breaker-b4-interrupted").apply { isDaemon = true } }
            try {
                // The status is set again before each call: a thread pool clears it while the coroutine is suspended.
                val outcome: Pair<StorageResult<*>, StorageResult<*>> = withContext(caller.asCoroutineDispatcher()) {
                    Thread.currentThread().interrupt()
                    val put: StorageResult<*> = store().put("a", "1")
                    Thread.currentThread().interrupt()
                    val get: StorageResult<*> = store(cache = OsKeyCache()).get("a")
                    Thread.interrupted()
                    put to get
                }
                assertEquals(StorageResult.Success(Unit), outcome.first, "default dispatcher: the put of an interrupted caller is served")
                assertEquals(StorageResult.Success("1"), outcome.second, "default dispatcher: and so is the get")
            } finally {
                caller.shutdownNow()
            }
        }
        withRig(dispatcher = Dispatchers.Unconfined) {
            assertEquals(StorageResult.Success(Unit), store().put("a", "1"), "control")
            Thread.currentThread().interrupt()
            val result: StorageResult<*> = store().get("a")
            assertTrue(Thread.interrupted(), "Unconfined: the status is kept")
            val failed = assertIs<StorageError.OperationFailed>(failure(result), "Unconfined: the call is refused")
            // 05 names ClosedByInterruptException as "its cause"; the files layer wraps it in an IOException (see the report).
            val chain: List<Throwable> = generateSequence(failed.cause) { it.cause }.toList()
            assertTrue(chain.any { it is ClosedByInterruptException }, "Unconfined: no ClosedByInterruptException in the cause chain: ${chain.map { it::class.java.simpleName }}")
        }
    }

    private companion object {
        val HANG_BOUND = 180.seconds
        const val CALL_BOUND_MS: Long = 30_000
        const val STACK_FRAMES: Int = 12
    }
}
