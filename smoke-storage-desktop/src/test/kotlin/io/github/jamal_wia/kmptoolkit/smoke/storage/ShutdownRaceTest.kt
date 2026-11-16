package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The hook against a store call that is still running when the signal arrives (breaker round 3, m1), with the run wired to the hook's stop
 * signal the way Main.kt wires it: the hook stops the run's next step, waits for the run's own cleanup, and cleans up itself only when the
 * run does not come back; the run's own removal never trusts the hook's old success. The reviewer's `ShutdownBreakerR3Test` checks the
 * outcome without the stop signal; these tests pin the mechanism. Every wait has a hard bound; the only real-time waits are the hook's own
 * short timeouts of the tests that exercise them.
 */
class ShutdownRaceTest {

    private val root: File = File("build/shutdown-race-test").absoluteFile
    private val item: MacKeyItem = MacKeyItem("smoke.race.kmptoolkit.securestorage", "dir-0123456789abcdef0123456789abcdef")
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

    // --- the stop signal ---

    @Test
    fun `the stop signal lets a step start until the hook has begun, and refuses every step after that`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, runWaitMillis = SHORT_WAIT_MILLIS)
        cleanup.stop.check()
        NeverStopped.check()
        cleanup.runNow()
        val refused: SmokeFailure = assertFailsWith<SmokeFailure> { cleanup.stop.check() }
        assertEquals(STOPPED_FROM_OUTSIDE, refused.message)
        NeverStopped.check()
    }

    @Test
    fun `a run that is stopped after its put ends at the next step, removes the item the put made with the control on, and runs no further step`() {
        val store: LatchedPutStore = LatchedPutStore(keychain, item, directory = null, blockPut = false)
        val stopAfterPut: StopSignal = StopSignal { if (store.itemWasMade) throw SmokeFailure(STOPPED_FROM_OUTSIDE) }
        val failure: Throwable? = runCatching {
            runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = MacKeyItemRemoval(keychain, item), stop = stopAfterPut) }
        }.exceptionOrNull()
        assertTrue(store.itemWasMade, "control: the put made the item before the stop")
        assertEquals(STOPPED_FROM_OUTSIDE, failure?.message, "the run ended with the stop as its reason")
        assertEquals(0, store.gets.get(), "no step after the stop ran")
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "the cleanup ran: control read, delete, proof of absence")
        assertTrue(keychain.items.isEmpty(), "the item the put made is removed")
        assertTrue(store.cleared)
    }

    // --- the hook and a store call in flight ---

    @Test
    fun `a hook that fires while the first put is in flight stops the run, waits for its cleanup, and removes the item once`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        val store: LatchedPutStore = LatchedPutStore(keychain, item, directory)
        val run: CompletableFuture<Throwable?> = CompletableFuture.supplyAsync {
            runCatching { runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = removal, stop = cleanup.stop) } }.exceptionOrNull()
        }
        var hook: CompletableFuture<Void>? = null
        try {
            assertTrue(store.putEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the first put is in flight")
            hook = CompletableFuture.runAsync { cleanup.runNow() }
            awaitClosing(cleanup)
            assertFalse(hook.isDone, "the hook waits for the run instead of cleaning up under it: the put has not landed yet")
        } finally {
            store.putRelease.countDown()
        }
        assertEquals(STOPPED_FROM_OUTSIDE, run.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)?.message, "the run stopped at its next step")
        hook!!.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        assertTrue(store.itemWasMade, "control: the put in flight did make the item")
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "ONE removal: the run's own, with the control; the hook did not delete")
        assertTrue(keychain.items.isEmpty())
        assertFalse(directory.exists(), "the directory the put recreated is gone")
        assertEquals(emptyList(), synchronized(reports) { reports.toList() })
    }

    @Test
    fun `a hook that times out waiting cleans up itself, and the run's later removal removes what the put made after that`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, runWaitMillis = SHORT_WAIT_MILLIS, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        val store: LatchedPutStore = LatchedPutStore(keychain, item, directory)
        val run: CompletableFuture<Throwable?> = CompletableFuture.supplyAsync {
            runCatching { runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = removal, stop = cleanup.stop) } }.exceptionOrNull()
        }
        try {
            assertTrue(store.putEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the first put is in flight and stays there")
            cleanup.runNow()
            assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "the hook removed on its own (the run did not come back), nothing was there yet")
            assertFalse(directory.exists(), "and deleted the directory")
        } finally {
            store.putRelease.countDown()
        }
        assertEquals(STOPPED_FROM_OUTSIDE, run.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)?.message)
        assertTrue(store.itemWasMade, "control: the put landed after the hook was done")
        assertTrue(keychain.items.isEmpty(), "the run did not trust the hook's old success: it removed the item that appeared after it")
        assertEquals(6, keychain.calls.size, "two full removals: the hook's and the run's own: ${keychain.calls}")
        assertEquals(emptyList(), synchronized(reports) { reports.toList() }, "the run's removal after the hook's found the item, no failure to report")
        assertTrue(cleanup.deleteDirectory(), "main's own directory step removes what the put recreated")
    }

    @Test
    fun `with main the hook waits for main's end of the run, not for the key item removal`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        File(directory, "f").writeText("x")
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, endsWithMain = true)
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        keychain.items[item.service to item.account] = ByteArray(32) { 1 }
        val hook: CompletableFuture<Void> = CompletableFuture.runAsync { cleanup.runNow() }
        removal.remove(expectPresent = true)
        cleanup.markRunFinished()
        hook.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        assertEquals(listOf("copy", "delete", "copy"), keychain.calls, "one removal")
        assertFalse(directory.exists())
    }

    @Test
    fun `a hook after a normal end does not report the failure of the run's own removal a second time`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, endsWithMain = true, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean): Unit = throw SmokeFailure("OSStatus -25293")
        })
        assertFailsWith<SmokeFailure> { removal.remove(expectPresent = true) }
        cleanup.markRunFinished()
        cleanup.runNow()
        assertEquals(emptyList(), synchronized(reports) { reports.toList() }, "the run reported its own failure in its verdict; the hook adds nothing")
    }

    // --- OnceRemoval: the hook's removal and the run's ---

    @Test
    fun `the run removes again after a hook removal, without insisting the item is still there`() {
        val told: MutableList<Boolean> = mutableListOf()
        val once: OnceRemoval = OnceRemoval(recording(told))
        once.removeFromHook()
        once.remove(expectPresent = true)
        assertEquals(listOf(false, false), told, "the hook's delete is not the run's: the run runs its own, and the hook may have deleted the item")
    }

    @Test
    fun `the run's own removal keeps its control when no hook removed before it`() {
        val told: MutableList<Boolean> = mutableListOf()
        val once: OnceRemoval = OnceRemoval(recording(told))
        once.remove(expectPresent = true)
        assertEquals(listOf(true), told)
    }

    @Test
    fun `a hook removal after the run's own removal waits for it and does not remove again`() {
        val told: MutableList<Boolean> = mutableListOf()
        val once: OnceRemoval = OnceRemoval(recording(told))
        once.remove(expectPresent = true)
        once.removeFromHook()
        assertEquals(listOf(true), told)
    }

    @Test
    fun `the hook's removal and the run's never use the binding at once`() {
        val inside: AtomicInteger = AtomicInteger()
        val maxInside: AtomicInteger = AtomicInteger()
        val hookEntered: CountDownLatch = CountDownLatch(1)
        val hookRelease: CountDownLatch = CountDownLatch(1)
        val order: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var firstCall: Boolean = true
        val once: OnceRemoval = OnceRemoval(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                val now: Int = inside.incrementAndGet()
                maxInside.updateAndGet { maxOf(it, now) }
                val hook: Boolean = synchronized(this) { firstCall.also { firstCall = false } }
                order += if (hook) "hook-start" else "run-start"
                if (hook) {
                    hookEntered.countDown()
                    hookRelease.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
                }
                order += if (hook) "hook-end" else "run-end"
                inside.decrementAndGet()
            }
        })
        val hook: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.removeFromHook() }
        try {
            assertTrue(hookEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the hook is inside the binding")
            val run: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.remove(expectPresent = true) }
            hookRelease.countDown()
            run.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
            hook.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        } finally {
            hookRelease.countDown()
        }
        assertEquals(1, maxInside.get(), "one thread in the binding at a time: $order")
        assertEquals(listOf("hook-start", "hook-end", "run-start", "run-end"), order.toList())
    }

    @Test
    fun `a run that waits too long for the binding that the hook holds fails instead of waiting for ever`() {
        val hookEntered: CountDownLatch = CountDownLatch(1)
        val hookRelease: CountDownLatch = CountDownLatch(1)
        val once: OnceRemoval = OnceRemoval(
            object : KeyItemRemoval {
                override fun remove(expectPresent: Boolean) {
                    hookEntered.countDown()
                    hookRelease.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
                }
            },
            waitMillis = SHORT_WAIT_MILLIS,
        )
        val hook: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.removeFromHook() }
        try {
            assertTrue(hookEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS))
            val failure: SmokeFailure = assertFailsWith<SmokeFailure> { once.remove(expectPresent = true) }
            assertTrue("still busy" in failure.message.orEmpty(), failure.message)
        } finally {
            hookRelease.countDown()
            hook.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the end of the run's own removal is told, on success and on failure`() {
        val ends: AtomicInteger = AtomicInteger()
        OnceRemoval(recording(mutableListOf()), onFinished = { ends.incrementAndGet() }).remove(expectPresent = true)
        val failing: OnceRemoval = OnceRemoval(
            object : KeyItemRemoval {
                override fun remove(expectPresent: Boolean): Unit = throw SmokeFailure("no")
            },
            onFinished = { ends.incrementAndGet() },
        )
        assertFailsWith<SmokeFailure> { failing.remove(expectPresent = true) }
        assertEquals(2, ends.get())
    }

    private fun recording(told: MutableList<Boolean>): KeyItemRemoval = object : KeyItemRemoval {
        override fun remove(expectPresent: Boolean) {
            told += expectPresent
        }
    }

    /** Spins (bounded, no sleep) until the hook has set its closing flag, which the stop signal shows. */
    private fun awaitClosing(cleanup: ShutdownCleanup) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(HARD_BOUND_SECONDS)
        while (runCatching { cleanup.stop.check() }.isSuccess) {
            check(System.nanoTime() < deadline) { "the hook never began" }
            Thread.yield()
        }
    }

    /**
     * A secure store whose first `put` makes its key item in [keychain] (and, with a [directory], its file there, making the directory again
     * if it is gone) only when [putRelease] opens when [blockPut]; counts the `get`s so a test can see which steps ran.
     */
    private class LatchedPutStore(
        private val keychain: FakeKeychain,
        private val itemOfTheStore: MacKeyItem,
        private val directory: File?,
        private val blockPut: Boolean = true,
    ) : SecureKeyValueStorage {
        private val data: MutableMap<String, String> = mutableMapOf()
        val putEntered: CountDownLatch = CountDownLatch(1)
        val putRelease: CountDownLatch = CountDownLatch(1)
        val gets: AtomicInteger = AtomicInteger()

        @Volatile
        var itemWasMade: Boolean = false

        @Volatile
        var cleared: Boolean = false

        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_USER_SCOPED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            putEntered.countDown()
            if (blockPut) check(putRelease.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)) { "the put was never released" }
            synchronized(keychain) { keychain.items[itemOfTheStore.service to itemOfTheStore.account] = ByteArray(32) { 1 } }
            directory?.let { dir: File ->
                dir.mkdirs()
                File(dir, "store.bin").writeText("ciphertext")
            }
            itemWasMade = true
            data[key] = value
            return StorageResult.Success(Unit)
        }

        override suspend fun get(key: String): StorageResult<String?> {
            gets.incrementAndGet()
            return StorageResult.Success(data[key])
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            data.remove(key)
            return StorageResult.Success(Unit)
        }

        override suspend fun clear(): StorageResult<Unit> {
            cleared = true
            data.clear()
            return StorageResult.Success(Unit)
        }
    }

    private companion object {
        const val HARD_BOUND_SECONDS: Long = 30L

        /** A hook's own wait for a run, shortened for the tests that need it to run out. */
        const val SHORT_WAIT_MILLIS: Long = 100L
    }
}
