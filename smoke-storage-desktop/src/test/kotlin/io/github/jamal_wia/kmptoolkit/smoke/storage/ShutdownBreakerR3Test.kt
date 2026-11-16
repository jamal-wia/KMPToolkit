package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Breaker round 3 of group sto-smoke (STO-14, packaging smoke): attacks on the round-3 fixes, the shutdown hook ([ShutdownCleanup],
 * [OnceRemoval]) wired to the secure run ([runSecureRoundTrip]) the way Main.kt wires them, over the fake keychain.
 *
 * Contract under attack (the KDoc of Shutdown.kt and the RELEASING text of the executor's hand-off): "A run that is stopped from outside
 * ... still removes its key item and its temporary directory". The JVM does not stop the main thread while it runs the shutdown hooks, so
 * a store call that is in flight when the signal arrives can still make the key item (and recreate the directory) after the hook has
 * cleaned up; and because the hook already used the one removal of [OnceRemoval], the run's own cleanup is then a no-op.
 * Every wait has a hard bound; no sleep.
 */
class ShutdownBreakerR3Test {

    private val root: File = File("build/shutdown-breaker-r3").absoluteFile
    private val item: MacKeyItem = MacKeyItem("smoke.r3.kmptoolkit.securestorage", "dir-00112233445566778899aabbccddeeff")
    private val keychain: FakeKeychain = FakeKeychain()

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        check(root.mkdirs()) { "could not create $root" }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    /**
     * Defect class: race (check-then-act between the hook's removal and an in-flight put) giving a leak of the key item and the directory.
     * Mutation by reading: today the hook removes at once (finds nothing), the put lands, and the run's own removal returns the hook's
     * stale success (OnceRemoval) => RED. A hook that first lets the step in flight finish (or makes the run stop and waits, bounded, for
     * the run's own cleanup) turns it GREEN. The observed counterpart in the real packaged app: breaker-r3/sigterm-packaged-app.sh.
     */
    @Test
    fun `a key item a put makes while the shutdown hook runs is not left behind, nor the directory the put recreates`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        val store: InFlightPutStore = InFlightPutStore(keychain, item, directory)
        val run: CompletableFuture<Throwable?> = CompletableFuture.supplyAsync {
            try {
                runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = removal) }
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        try {
            assertTrue(store.putEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the first put is in flight")
            // The signal arrives now. A hook that does not wait for the call in flight finishes at once; one that waits is still waiting
            // when the grace below ends. Either way the in-flight put lands next, as it does in a real process.
            val hook: CompletableFuture<Void> = CompletableFuture.runAsync { cleanup.runNow() }
            try {
                hook.get(GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // the hook waits for the step in flight: the fixed shape
            }
            store.putRelease.countDown()
            hook.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
            run.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        } finally {
            store.putRelease.countDown()
        }
        assertTrue(store.itemWasMade, "control of the fake: the put did make the key item")
        val left: List<String> = keychain.items.keys.map { (service: String, account: String) -> "$service/$account" }
        assertTrue(
            left.isEmpty(),
            "the key item made by the put in flight is left in the keychain after the hook and the run's own cleanup: $left; " +
                "keychain calls=${keychain.calls}; hook reports=$reports; run outcome=${run.getNow(null)}",
        )
        assertFalse(directory.exists(), "the directory the put recreated after the hook deleted it is left: ${directory.list()?.toList()}")
    }

    /**
     * Defect class: wrong terminal state (a delete that says "not found" while the item is still there must not pass as removed).
     * Mutation by reading: dropping the read after the delete in MacKeyItemRemoval turns it RED.
     */
    @Test
    fun `a delete that answers not-found while the item is still there is reported next to the run's failure`() {
        keychain.deleteStatus = STATUS_ITEM_NOT_FOUND
        val store: InFlightPutStore = InFlightPutStore(keychain, item, directory = null, blockPut = false).apply { failGet = true }
        val failure: Throwable? = runCatching {
            runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = MacKeyItemRemoval(keychain, item)) }
        }.exceptionOrNull()
        assertTrue(store.itemWasMade, "control: the item existed before the cleanup")
        assertTrue(failure is SmokeFailure && "secure get failed" in failure.message.orEmpty(), "the verdict names the first failure: $failure")
        val details: List<String> = failureDetails(failure)
        assertTrue(details.any { "still in the login keychain" in it }, "the item that survived a 'not found' delete is reported: $details")
        assertEquals(1, keychain.items.size, "control: the item really is still there")
    }

    /** The same on a run that otherwise passes: the run must fail, never PASS with the item left. */
    @Test
    fun `a passing round trip whose delete answers not-found while the item is still there fails`() {
        keychain.deleteStatus = STATUS_ITEM_NOT_FOUND
        val store: InFlightPutStore = InFlightPutStore(keychain, item, directory = null, blockPut = false)
        val failure: Throwable? = runCatching {
            runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = MacKeyItemRemoval(keychain, item)) }
        }.exceptionOrNull()
        assertTrue(failure is SmokeFailure && "still in the login keychain" in failure.message.orEmpty(), "a left item fails the run: $failure")
    }

    /**
     * Defect class: double removal / a hook that lets the process end under the run's removal. The run's own removal (the real
     * MacKeyItemRemoval, held inside its first read) is in progress when the hook fires: the hook must not delete a second time and must not
     * report a failure once the run's removal has finished. Mutation by reading: OnceRemoval without its compare-and-set gives two deletes.
     */
    @Test
    fun `a hook that fires while the run's own removal is in progress neither deletes twice nor reports a failure`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        keychain.items[item.service to item.account] = ByteArray(32) { 7 }
        val insideRead: CountDownLatch = CountDownLatch(1)
        val releaseRead: CountDownLatch = CountDownLatch(1)
        var first: Boolean = true
        keychain.beforeCopy = {
            if (first) {
                first = false
                insideRead.countDown()
                releaseRead.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
            }
        }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        val runRemoval: CompletableFuture<Void> = CompletableFuture.runAsync { removal.remove(expectPresent = true) }
        val hook: CompletableFuture<Void>
        try {
            assertTrue(insideRead.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the run's removal is inside its first read")
            hook = CompletableFuture.runAsync { cleanup.runNow() }
        } finally {
            releaseRead.countDown()
        }
        runRemoval.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        hook.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        assertEquals(1, keychain.calls.count { it == "delete" }, "one delete, whoever asked: ${keychain.calls}")
        assertTrue(keychain.items.isEmpty())
        assertEquals(emptyList(), synchronized(reports) { reports.toList() })
        assertFalse(directory.exists())
    }

    /**
     * A secure store whose first `put` makes its key item in [keychain] (and, when [directory] is given, its file in that directory, making
     * the directory again if it is gone, as the library's store does) only when [putRelease] opens, so the test can hold it "inside the
     * native call".
     */
    private class InFlightPutStore(
        private val keychain: FakeKeychain,
        private val itemOfTheStore: MacKeyItem,
        private val directory: File?,
        private val blockPut: Boolean = true,
    ) : SecureKeyValueStorage {
        private val data: MutableMap<String, String> = mutableMapOf()
        val putEntered: CountDownLatch = CountDownLatch(1)
        val putRelease: CountDownLatch = CountDownLatch(1)

        @Volatile
        var itemWasMade: Boolean = false

        @Volatile
        var failGet: Boolean = false

        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_USER_SCOPED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            if (!itemWasMade) {
                putEntered.countDown()
                if (blockPut) check(putRelease.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)) { "the put was never released" }
                synchronized(keychain) { keychain.items[itemOfTheStore.service to itemOfTheStore.account] = ByteArray(32) { 1 } }
                directory?.let { dir: File ->
                    dir.mkdirs()
                    File(dir, "store.bin").writeText("ciphertext")
                }
                itemWasMade = true
            }
            data[key] = value
            return StorageResult.Success(Unit)
        }

        override suspend fun get(key: String): StorageResult<String?> {
            if (failGet) return StorageResult.Failure(StorageError.OperationFailed(StorageOperation.GET, key = key))
            return StorageResult.Success(data[key])
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            data.remove(key)
            return StorageResult.Success(Unit)
        }

        override suspend fun clear(): StorageResult<Unit> {
            data.clear()
            return StorageResult.Success(Unit)
        }
    }

    private companion object {
        const val HARD_BOUND_SECONDS: Long = 30L

        /** How long the test lets a hook wait for the call in flight before the call lands anyway (a give-up bound, not a timing assertion). */
        const val GRACE_MILLIS: Long = 2_000L
        const val STATUS_ITEM_NOT_FOUND: Int = -25300
    }
}
