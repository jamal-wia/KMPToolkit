package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopKeyStore
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 3 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-3): the phase A fixes of round 5
 * (task/sto-desktop ea7fa0a8) as they reach the PHASE B flows, through the store API only.
 *
 * - D-P4: a caller whose thread is interrupted gets a typed result and keeps its interrupt status; a native thread that
 *   cannot be made answers that one call as failed, is never cached as a verdict, and the next call works.
 * - D-P8: the lock file the no-link fallback creates is owner-only, and a DACL that cannot be built fails the call CLOSED:
 *   a typed `OperationFailed` with the right operation, and no store file, no lock file, no OS call, no descriptor, no
 *   claim left behind.
 * - plan 4.4: a native failure is never "key lost" (the user's file is never quarantined for it).
 *
 * Fake binding only, real files in the rig's directory, hang bounds only.
 */
class DesktopSecureStoreBreakerR4B3Test {

    private fun <T> withRig(
        os: DesktopOs = DesktopOs.MACOS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend SecureTestRig.() -> T,
    ): T =
        SecureTestRig(os, dispatcher = dispatcher).use { rig: SecureTestRig -> runBlocking { withTimeout(HANG_BOUND) { rig.block() } } }

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    // ---- interrupted callers ----

    /**
     * Every operation of a store whose key is cached runs without suspending, so it runs entirely on a thread whose interrupt
     * status is set (the store's dispatcher is `Unconfined`: no hop clears it). D-P4 and the round-5 CHANGELOG line: such a
     * caller gets a typed answer, never an exception, and keeps its interrupt status. Each answer is `Success` or a retryable
     * `OperationFailed` (the JDK closes an interruptible file channel of an interrupted thread: `ClosedByInterruptException`),
     * and whatever it answered, the store is left as a clean caller would find it: an operation that answered `Success` took
     * effect, one that failed took none, no temporary file, no quarantine, no lock or claim left. Red on: an exception out of
     * a store call, a cleared status, a failed write that left a temporary file or a torn store, a lock left held.
     */
    @Test
    fun `every operation of a warm store on an interrupted thread answers typed, keeps the interrupt status and leaves no half state`() = withRig(dispatcher = Dispatchers.Unconfined) {
        val expectedNames: MutableSet<String> = mutableSetOf()
        for ((label: String, open: () -> EncryptedKeyValueStorage) in listOf<Pair<String, () -> EncryptedKeyValueStorage>>(
            "OS-key store" to { store() },
            "app-key store" to { JvmAppKeyEncryptedKeyValueStorage("app", directory, AppEncryptionKey.Raw { ByteArray(32) { 9 } }, dispatcher = Dispatchers.Unconfined) },
        )) {
            val store: EncryptedKeyValueStorage = open()
            assertEquals(StorageResult.Success(Unit), store.put("warm", "0"), "$label: control, a clean caller warms the key")
            val data: File = directory.listFiles()!!.single { it.name.endsWith(".bin") && it.name !in expectedNames }
            expectedNames += data.name
            expectedNames += CrossProcessLock.lockFileOf(data).name
            val model: MutableMap<String, String> = mutableMapOf("warm" to "0")

            val operations: List<Triple<String, suspend () -> StorageResult<*>, () -> Unit>> = listOf(
                Triple("put", { store.put("a", "1") }, { model["a"] = "1" }),
                Triple("get", { store.get("a") }, {}),
                Triple("ensureKey", { store.ensureKey() }, {}),
                Triple("remove", { store.remove("warm") }, { model.remove("warm") }),
                Triple("put again", { store.put("b", "2") }, { model["b"] = "2" }),
                Triple("clear", { store.clear() }, { model.clear() }),
            )
            val answers: MutableList<String> = mutableListOf()
            for ((name: String, operation: suspend () -> StorageResult<*>, apply: () -> Unit) in operations) {
                Thread.currentThread().interrupt()
                val outcome: Result<StorageResult<*>> = runCatching { operation() }
                val kept: Boolean = Thread.interrupted()
                assertTrue(outcome.isSuccess, "$label $name threw ${outcome.exceptionOrNull()}")
                assertTrue(kept, "$label $name cleared the caller's interrupt status")
                when (val result: StorageResult<*> = outcome.getOrThrow()) {
                    is StorageResult.Success -> apply()
                    is StorageResult.Failure -> assertIs<StorageError.OperationFailed>(result.error, "$label $name: $result")
                }
                answers += "$name=${outcome.getOrThrow()::class.simpleName}"
                assertEquals(expectedNames, fileNames().toSet(), "$label $name ($answers): a temporary or quarantine file was left")
                val reader: EncryptedKeyValueStorage = open()
                for (key: String in listOf("warm", "a", "b")) {
                    assertEquals(StorageResult.Success(model[key]), reader.get(key), "$label after $name ($answers): '$key' is not what the answers say")
                }
            }
            assertEquals(StorageResult.Success(Unit), store.put("after", "3"), "$label: the store works for a clean caller")
            assertEquals(StorageResult.Success("3"), open().get("after"), "$label: and for a new instance (no lock or claim left)")
        }
    }

    // ---- a native thread that cannot be made ----

    /** A thread factory that fails its first [failures] requests with [error], then makes daemon threads. */
    private class FailingFactory(failures: Int, private val error: () -> Throwable) : ThreadFactory {
        private val left = AtomicInteger(failures)
        val asked = AtomicInteger()

        override fun newThread(runnable: Runnable): Thread {
            asked.incrementAndGet()
            if (left.getAndDecrement() > 0) throw error()
            return Thread(runnable, "breaker-native").apply { isDaemon = true }
        }
    }

    /**
     * The first put of a store needs the native thread to install the key. If the thread cannot be made: the put is a typed
     * `OperationFailed(PUT, key)`, the create is rolled back (no store file, no item), and the NEXT put works and makes exactly
     * one item. Both an `OutOfMemoryError` and a `SecurityException` (a container that forbids threads: NOT a binding that
     * cannot run, so never a process-wide `Unsupported`). Red on: the start failure thrown out of `put`, a half-created store,
     * or a start failure classified and kept as `NATIVE_ACCESS_UNAVAILABLE`.
     */
    @Test
    fun `a create whose native thread cannot be made fails typed, rolls back, and the next put works`() {
        for ((label: String, error: () -> Throwable) in listOf<Pair<String, () -> Throwable>>(
            "OutOfMemoryError" to { OutOfMemoryError("unable to create native thread") },
            "SecurityException" to { SecurityException("threads are not allowed here") },
        )) {
            withRig {
                val factory = FailingFactory(1, error)
                NativeExecutor(factory).use { executor: NativeExecutor ->
                    val store = store(executor = executor)

                    val refused: StorageError = failure(store.put("k", "v"))
                    val failed = assertIs<StorageError.OperationFailed>(refused, "$label: $refused")
                    assertEquals(StorageOperation.PUT, failed.operation, label)
                    assertEquals("k", failed.key, label)
                    assertTrue(!dataFile().exists(), "$label: the create was not rolled back: ${fileNames()}")
                    assertEquals(emptyMap(), fake.macItems.toMap(), "$label: an item was left")
                    assertEquals(1, factory.asked.get(), "$label: control, the thread was asked for")

                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$label: the next put")
                    assertEquals(1, fake.macItems.size, "$label: exactly one item")
                    assertEquals(StorageResult.Success("v"), store(executor = executor, cache = OsKeyCache()).get("k"), label)
                }
            }
        }
    }

    /**
     * `ensureKey()` whose native thread cannot be made: `OperationFailed(ENSURE_KEY, key = null)` (D-15), nothing created.
     */
    @Test
    fun `ensureKey whose native thread cannot be made is OperationFailed of ENSURE_KEY with no key`() = withRig {
        NativeExecutor(FailingFactory(1) { OutOfMemoryError("unable to create native thread") }).use { executor: NativeExecutor ->
            val failed = assertIs<StorageError.OperationFailed>(failure(store(executor = executor).ensureKey()))
            assertEquals(StorageOperation.ENSURE_KEY, failed.operation)
            assertNull(failed.key)
            assertEquals(emptyList(), fileNames().filter { it.endsWith(".bin") })
            assertEquals(StorageResult.Success(Unit), store(executor = executor).ensureKey(), "the next call works")
        }
    }

    /**
     * An existing store with a cold cache must FETCH its key. If the native thread cannot be made, that is a failure of this
     * call, never "key lost" (plan 4.4: only a definitive not-found, a failing key-check or a foreign header are): a get is
     * `OperationFailed`, a put does not quarantine, the file stays byte-identical; on macOS, Linux and Windows. Red on: a
     * `Threw` outcome mapped to not-found.
     */
    @Test
    fun `a key fetch whose native thread cannot be made is never key lost, on every OS`() {
        for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
            withRig(os) {
                assertEquals(StorageResult.Success(Unit), store().put("a", "1"), "$os: control")
                val before: ByteArray = dataFile().readBytes()
                NativeExecutor(FailingFactory(2) { OutOfMemoryError("unable to create native thread") }).use { executor: NativeExecutor ->
                    assertIs<StorageError.OperationFailed>(failure(store(executor = executor, cache = OsKeyCache()).get("a")), "$os get")
                    assertIs<StorageError.OperationFailed>(failure(store(executor = executor, cache = OsKeyCache()).put("b", "2")), "$os put")
                    assertContentEquals(before, dataFile().readBytes(), "$os: the user's file changed")
                    assertEquals(emptyList(), quarantined(), "$os: quarantined for a native failure")
                    assertEquals(StorageResult.Success("1"), store(executor = executor, cache = OsKeyCache()).get("a"), "$os: the next call works")
                }
            }
        }
    }

    /** The probe whose native thread cannot be made: a typed failure, no throw, and the next probe answers. */
    @Test
    fun `probeDesktopKeyStore whose native thread cannot be made answers typed and the next probe works`() = withRig {
        NativeExecutor(FailingFactory(1) { OutOfMemoryError("unable to create native thread") }).use { executor: NativeExecutor ->
            val probe = DesktopKeyStoreProbe(source(), executor, SecureStoreEnvironment())
            assertIs<StorageError.OperationFailed>(failure(probe.probe(DesktopSecureStorageOptions())))
            assertIs<StorageResult.Success<DesktopKeyStore>>(probe.probe(DesktopSecureStorageOptions()), "the next probe")
        }
        Unit
    }

    // ---- the no-link lock file, failing closed ----

    private val noLinks: FileAccess = object : FileAccess by createFileAccess() {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
            FileResult.Failure(FileError.Unsupported(path))
    }

    private class Nobody : UserPrincipalLookupService() {
        override fun lookupPrincipalByName(name: String): UserPrincipal = throw UserPrincipalNotFoundException(name)

        override fun lookupPrincipalByGroupName(group: String): GroupPrincipal = throw UserPrincipalNotFoundException(group)
    }

    /** A volume without hard links whose ACL view cannot get the owner-only DACL built (the Windows branch, by its seams). */
    private fun failingLock(): CrossProcessLock = CrossProcessLock(
        noLinks,
        lockFileAttributes = LockFileAttributes(supportedViews = { _: Path -> setOf("acl") }, tokenUserName = { "CORP\\nobody" }, lookup = { _: Path -> Nobody() }),
    )

    /**
     * D-P8 through the store: every operation of an OS-key store and of an app-key store on such a volume answers
     * `OperationFailed` with its own operation and key, and leaves no store file, no lock file, no OS call, no descriptor;
     * afterwards a store with a working lock over the same directory works (no claim was left). Red on: a lock file created
     * without the DACL, a call that goes ahead unlocked, a leaked claim or channel.
     */
    @Test
    fun `a lock file whose owner-only DACL cannot be built fails every store call closed and leaves nothing`() = withRig {
        val descriptors: Long = TestFs.requireOpenDescriptorCount()
        val stores: List<Pair<String, EncryptedKeyValueStorage>> = listOf(
            "OS-key" to store(lock = failingLock()),
            "app-key" to JvmAppKeyEncryptedKeyValueStorage("app", directory, AppEncryptionKey.Raw { ByteArray(32) { 1 } }, crossProcessLock = failingLock()),
        )
        for ((label: String, store: EncryptedKeyValueStorage) in stores) {
            val expected: List<Triple<StorageOperation, String?, suspend () -> StorageResult<*>>> = listOf(
                Triple(StorageOperation.PUT, "k", { store.put("k", "v") }),
                Triple(StorageOperation.ENSURE_KEY, null, { store.ensureKey() }),
                Triple(StorageOperation.GET, "k", { store.get("k") }),
                Triple(StorageOperation.REMOVE, "k", { store.remove("k") }),
                Triple(StorageOperation.CLEAR, null, { store.clear() }),
            )
            for ((operation: StorageOperation, key: String?, call: suspend () -> StorageResult<*>) in expected) {
                val failed = assertIs<StorageError.OperationFailed>(failure(call()), "$label $operation")
                assertEquals(operation, failed.operation, "$label $operation")
                assertEquals(key, failed.key, "$label $operation")
            }
            assertEquals(emptyList(), fileNames(), "$label: a file was created: ${fileNames()}")
        }
        assertEquals(emptyList(), fake.calls, "an OS call was made without the lock")
        assertEquals(descriptors, TestFs.requireOpenDescriptorCount(), "a descriptor was left open")

        assertEquals(StorageResult.Success(Unit), store().put("k", "v"), "a working lock over the same directory")
        assertEquals(StorageResult.Success("v"), store(cache = OsKeyCache()).get("k"))
    }

    /**
     * The same on an EXISTING store whose lock file is gone (deleted by hand): the call fails closed with the store file
     * byte-identical, nothing quarantined, no lock file. Red on: a store that reads or writes its file without the lock when
     * the lock file cannot be made.
     */
    @Test
    fun `an existing store whose lock file cannot be recreated owner-only is never read or written`() = withRig {
        assertEquals(StorageResult.Success(Unit), store().put("a", "1"))
        val lockFile: File = CrossProcessLock.lockFileOf(dataFile())
        assertTrue(lockFile.delete(), "control: the lock file existed")
        val before: ByteArray = dataFile().readBytes()
        val calls: Int = fake.calls.size

        val locked = store(lock = failingLock(), cache = OsKeyCache())
        assertIs<StorageError.OperationFailed>(failure(locked.put("a", "2")))
        assertIs<StorageError.OperationFailed>(failure(locked.get("a")))
        assertIs<StorageError.OperationFailed>(failure(locked.clear()))

        assertContentEquals(before, dataFile().readBytes(), "the store file changed without its lock")
        assertTrue(!lockFile.exists(), "a lock file was created without the owner-only DACL")
        assertEquals(calls, fake.calls.size, "an OS call was made without the lock")
        assertEquals(emptyList(), quarantined())
        assertEquals(StorageResult.Success("1"), store(cache = OsKeyCache()).get("a"), "the data is intact")
    }

    private companion object {
        val HANG_BOUND = 60.seconds
    }
}
