@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import io.github.jamal_wia.kmptoolkit.storage.secureStoreId
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Hostile cases of the OS-key store (group sto-desktop, round 3): the Windows and Linux paths, hung and cancelled native
 * calls, a file system that refuses locks, the lock budget of reads, a seeded storm, handle release and re-init. Every wait
 * on another thread has a hang bound; limits run on virtual time (`runTest`) only where the real native thread is blocked on a
 * latch, so an idle scheduler firing the limit is exactly the scenario.
 */
class OsKeyStoreHostileTest {

    private fun assertFailure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result).error

    private fun <T> withRig(os: DesktopOs, block: suspend SecureTestRig.() -> T): T =
        SecureTestRig(os).use { rig: SecureTestRig -> runBlocking { rig.block() } }

    private fun await(latch: CountDownLatch, what: String) {
        assertTrue(latch.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "timed out waiting for $what")
    }

    private fun SecureTestRig.freshCache(): OsKeyCache = OsKeyCache()

    // ---- Windows ----

    @Test
    fun `Windows keeps the DPAPI blob in the header and a file under another name cannot be unwrapped`() = withRig(DesktopOs.WINDOWS) {
        val store: JvmSecureKeyValueStorage = store()
        assertTrue(store.put("k", "v") is StorageResult.Success)
        val parsed = assertIs<ParseResult.Parsed>(SecureFileFormat.parse(dataFile().readBytes())).file
        assertEquals(StoreKind.DPAPI, parsed.kind)
        assertEquals(listOf("protect"), fake.callNames, "creating a key is one protect call and no item")

        assertEquals(StorageResult.Success("v"), store(cache = freshCache()).get("k"))
        assertEquals(1, fake.count("unprotect"), "an empty cache unwraps once")

        // Store A's file renamed onto store B's name: the entropy differs, DPAPI works, the key is lost.
        dataFile().copyTo(dataFile("other"))
        assertEquals(StorageError.Undecryptable(null), assertFailure(store("other", cache = freshCache()).get("k")))
    }

    @Test
    fun `Windows with a broken DPAPI answers Unavailable and with no logon session Unsupported, never Undecryptable`() = withRig(DesktopOs.WINDOWS) {
        assertTrue(store().put("k", "v") is StorageResult.Success)
        fake.forcedStatus["unprotect"] = 13

        assertIs<StorageError.Unavailable>(assertFailure(store(cache = freshCache()).get("k")))

        fake.forcedStatus.clear()
        fake.dpapiNoSession = true
        assertEquals(
            StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION),
            assertFailure(store(cache = freshCache()).get("k")),
        )
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION), assertFailure(store("fresh").put("k", "v")))
        assertFalse(dataFile("fresh").exists(), "a failed protect creates nothing")
    }

    // ---- Linux ----

    @Test
    fun `Linux stores the base64 data key under both attributes and reads it back`() = withRig(DesktopOs.LINUX) {
        val store: JvmSecureKeyValueStorage = store()
        assertTrue(store.put("k", "v") is StorageResult.Success)

        val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, directory)
        val item: FakeNativeKeyStoreBinding.SecretItem = fake.secretItems.getValue(identity.linuxAttributes)
        assertEquals(32, Base64.getDecoder().decode(item.secret).size)
        assertEquals(StorageResult.Success("v"), store(cache = freshCache()).get("k"))
        assertEquals(1, fake.count("lookup"))
    }

    @Test
    fun `Linux without a session bus is Unsupported before any native call`() = withRig(DesktopOs.LINUX) {
        hasSessionBus = false
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION), assertFailure(store().put("k", "v")))
        assertEquals(0, fake.callNames.size)
        assertFalse(dataFile().exists())
    }

    @Test
    fun `Linux without interaction refuses a create against a locked collection before the file is touched`() = withRig(DesktopOs.LINUX) {
        fake.defaultCollectionIsLocked = true
        val store: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false))

        assertIs<StorageError.Unavailable>(assertFailure(store.put("k", "v")))

        assertFalse(dataFile().exists())
        assertEquals(listOf("defaultCollectionLocked"), fake.callNames, "no store or clear call reached the collection")
    }

    @Test
    fun `Linux locked match is Unavailable without UI and AccessDenied after a dismissed prompt, never lost`() = withRig(DesktopOs.LINUX) {
        assertTrue(store().put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile().readBytes()
        fake.secretItems.values.single().locked = true

        val quiet: JvmSecureKeyValueStorage = store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cache = freshCache())
        assertIs<StorageError.Unavailable>(assertFailure(quiet.get("k")))
        fake.promptDismissed = true
        val asking: JvmSecureKeyValueStorage = store(cache = freshCache())
        assertIs<StorageError.AccessDenied>(assertFailure(asking.put("k", "w")))

        assertContentEquals(before, dataFile().readBytes())
        assertTrue(fileNames().none { ".undecryptable-" in it })
        assertEquals(1, fake.count("store"), "no key was ever created again")
    }

    @Test
    fun `Linux without the search symbol is Unsupported NO_KEY_STORE and not NATIVE_ACCESS_UNAVAILABLE`() = withRig(DesktopOs.LINUX) {
        assertTrue(store().put("k", "v") is StorageResult.Success)
        fake.searchSymbolMissing = true
        assertEquals(StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE), assertFailure(store(cache = freshCache()).get("k")))
    }

    // ---- a hung or cancelled native call ----

    @Test
    fun `a hung non-interactive fetch answers Unavailable at the limit, touches nothing and the thread count stays one`() = runTest {
        val rig = SecureTestRig(DesktopOs.MACOS, dispatcher = StandardTestDispatcher(testScheduler))
        rig.use {
            val threads = CopyOnWriteArraySet<Thread>()
            val seedStore: JvmSecureKeyValueStorage = rig.store()
            assertTrue(seedStore.put("k", "v") is StorageResult.Success)
            val before: ByteArray = rig.dataFile().readBytes()
            val hang = CountDownLatch(1)
            val entered = CountDownLatch(1)
            rig.fake.before = { name: String ->
                threads += Thread.currentThread()
                if (name == "copyGenericPassword") {
                    entered.countDown()
                    await(hang, "the test to release the hung call")
                }
            }
            val store: JvmSecureKeyValueStorage = rig.store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cache = OsKeyCache())

            val answer: StorageResult<String?> = store.get("k")

            assertIs<StorageError.Unavailable>(assertFailure(answer))
            assertEquals(0L, entered.count, "control: the native call was really running")
            assertContentEquals(before, rig.dataFile().readBytes())
            assertEquals(1, threads.size, "one native thread serves every call")
            hang.countDown()
            // The late result was discarded; the executor is usable again.
            rig.fake.before = {}
            assertEquals(StorageResult.Success("v"), store.get("k"))
        }
    }

    @Test
    fun `cancelling a caller during key creation rolls the file back, releases both locks and leaves the store usable`() = runBlocking {
        SecureTestRig(DesktopOs.MACOS).use { rig ->
            val entered = CountDownLatch(1)
            val letGo = CountDownLatch(1)
            rig.fake.before = { name: String ->
                if (name == "addGenericPassword") {
                    entered.countDown()
                    await(letGo, "the test to release the add call")
                }
            }
            val store: JvmSecureKeyValueStorage = rig.store()
            val caller = async(Dispatchers.Default) { store.put("k", "v") }
            await(entered, "the add call to start")
            assertTrue(rig.dataFile().exists(), "control: the empty file exists while the item is being added")

            caller.cancelAndJoin()

            assertFalse(rig.dataFile().exists(), "the created file was rolled back")
            assertTrue(rig.fileNames().none { it.endsWith(".tmp") })
            letGo.countDown()
            rig.fake.before = {}
            // Both locks are free; the late-added item is replaced by the next create (delete, add).
            assertEquals(StorageResult.Success(Unit), store.put("k", "v2"))
            assertEquals(StorageResult.Success("v2"), rig.store(cache = OsKeyCache()).get("k"))
        }
    }

    @Test
    fun `a binding that throws a LinkageError answers NATIVE_ACCESS_UNAVAILABLE and creates nothing`() = withRig(DesktopOs.MACOS) {
        fake.before = { throw UnsatisfiedLinkError("jnidispatch") }

        val error: StorageError = assertFailure(store().put("k", "v"))

        assertEquals(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, assertIs<StorageError.Unsupported>(error).reason)
        assertFalse(dataFile().exists())
    }

    // ---- locks ----

    @Test
    fun `a file system that refuses file locks fails closed, with the file byte-identical and zero binding calls`() = withRig(DesktopOs.MACOS) {
        assertTrue(store().put("k", "v") is StorageResult.Success)
        val before: ByteArray = dataFile().readBytes()
        val calls: Int = fake.callNames.size
        val refusing: JvmSecureKeyValueStorage = store(lock = CrossProcessLock(tryLock = { _: FileChannel -> throw IOException("no locks here (test)") }), cache = freshCache())

        for (result: StorageResult<*> in listOf(refusing.get("k"), refusing.put("k", "w"), refusing.remove("k"), refusing.clear(), refusing.ensureKey())) {
            val error: StorageError.OperationFailed = assertIs(assertFailure(result))
            assertIs<IOException>(error.cause)
        }
        assertContentEquals(before, dataFile().readBytes())
        assertEquals(calls, fake.callNames.size, "the fake recorded no call")
    }

    private fun TestScope.timedRig(): SecureTestRig =
        SecureTestRig(DesktopOs.MACOS, dispatcher = StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)

    @Test
    fun `a read behind a foreign lock holder answers Unavailable at exactly ten seconds and calls nothing`() = runTest {
        timedRig().use { rig ->
            val lock = CrossProcessLock(deadline = 10.seconds)
            assertTrue(rig.store(lock = lock).put("k", "v") is StorageResult.Success)
            val callsBefore: Int = rig.fake.callNames.size
            val holder: LockAcquisition.Held = assertIs(
                CrossProcessLock().acquire(CrossProcessLock.lockFileOf(rig.dataFile()), "p"),
            )
            val start: Long = testScheduler.currentTime

            val answer: StorageResult<String?> = rig.store(lock = lock, cache = OsKeyCache()).get("k")

            assertIs<StorageError.Unavailable>(assertFailure(answer))
            assertEquals(10_000L, testScheduler.currentTime - start)
            assertEquals(callsBefore, rig.fake.callNames.size, "no native call while waiting for the lock")
            holder.close()
            assertEquals(StorageResult.Success("v"), rig.store(lock = lock, cache = OsKeyCache()).get("k"))
        }
    }

    // ---- storm, handles, re-init ----

    @Test
    fun `a seeded storm over four instances keeps every read whole, creates one key and leaks no handle`() = withRig(DesktopOs.MACOS) {
        val seed = 20_261_115L
        val stores: List<JvmSecureKeyValueStorage> = List(4) { store() }
        assertTrue(stores[0].put("warm", "up") is StorageResult.Success)
        val handlesBefore: Long = TestFs.requireOpenDescriptorCount()
        val written: Map<String, MutableSet<String>> = (0 until 5).associate { "k$it" to java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
        val problems: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        coroutineScope {
            (0 until 8).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(40) { step: Int ->
                        val key = "k${random.nextInt(5)}"
                        val store: JvmSecureKeyValueStorage = stores[random.nextInt(4)]
                        when (random.nextInt(3)) {
                            0 -> {
                                val value = "w$worker-$step"
                                written.getValue(key) += value
                                val result: StorageResult<Unit> = store.put(key, value)
                                if (result !is StorageResult.Success) problems += "seed=$seed put $key: $result"
                            }
                            1 -> when (val result: StorageResult<String?> = store.get(key)) {
                                is StorageResult.Success -> result.value?.let { if (it !in written.getValue(key)) problems += "seed=$seed get $key invented '$it'" }
                                is StorageResult.Failure -> problems += "seed=$seed get $key: $result"
                            }
                            else -> if (store.remove(key) !is StorageResult.Success) problems += "seed=$seed remove $key"
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(emptyList(), problems.toList())
        assertEquals(1, fake.count("addGenericPassword"), "seed=$seed: one key for the whole storm")
        assertEquals(handlesBefore, TestFs.requireOpenDescriptorCount(), "seed=$seed: file handles leaked")
        assertTrue(fileNames().none { it.endsWith(".tmp") }, "seed=$seed: a temporary file is left")
    }

    @Test
    fun `re-initialisation after release works and the native thread ends with its executor`() = runBlocking {
        val first = SecureTestRig(DesktopOs.MACOS)
        val directory: File = first.directory
        val store: JvmSecureKeyValueStorage = first.store()
        assertTrue(store.put("k", "v") is StorageResult.Success)
        assertEquals(0, first.executor.pendingCalls)
        first.executor.close()
        assertTrue(first.executor.isClosed)

        val secondExecutor = NativeExecutor()
        try {
            val reopened: JvmSecureKeyValueStorage = first.store(executor = secondExecutor, cache = OsKeyCache())
            assertEquals(StorageResult.Success("v"), reopened.get("k"))
            assertEquals(1, first.fake.count("copyGenericPassword"))
        } finally {
            secondExecutor.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a store name with spaces and non-ASCII letters round-trips`() = withRig(DesktopOs.MACOS) {
        val name = "weird name ü ${secureStoreId("x")}"
        val store: JvmSecureKeyValueStorage = store(name = name)
        assertTrue(store.put("k", "v") is StorageResult.Success)
        assertEquals(StorageResult.Success("v"), store(name = name, cache = freshCache()).get("k"))
    }

    private companion object {
        const val HANG_BOUND_SECONDS: Long = 60
    }
}
