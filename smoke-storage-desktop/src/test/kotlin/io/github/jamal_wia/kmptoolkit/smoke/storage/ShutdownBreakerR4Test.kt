package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
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
 * Breaker round 4 of group sto-smoke (STO-14, packaging smoke): attacks on the round-4 fix of m1 (the hook sets `closing`, waits, bounded,
 * for the run, and cleans up itself only on a timeout), with the hook wired the way Main.kt wires it (`endsWithMain = true`: the run is
 * over only when `main` says so, after its own cleanup and its directory work), over the fake keychain. Every wait has a hard bound; no sleep.
 */
class ShutdownBreakerR4Test {

    private val root: File = File("build/shutdown-breaker-r4").absoluteFile
    private val item: MacKeyItem = MacKeyItem("smoke.r4.kmptoolkit.securestorage", "dir-ffeeddccbbaa99887766554433221100")
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
     * Defect class: race (two hook threads, a put in flight) and leak. With `main`, the hook must wait for `main`'s end, not for the key item
     * removal, and a second hook thread must neither clean up under the run nor make the run's removal run twice. Mutation by reading: a hook
     * that completes its wait on the removal's end instead of `main`'s (ignores `endsWithMain`), or that does not wait at all, deletes the
     * directory before `main` has finished with it and turns the `main`-not-done assertion RED; a hook that claims the once slot turns the
     * left-item assertion RED (the run's removal would return the hook's stale success).
     */
    @Test
    fun `with main, two hooks that fire while the first put is in flight wait for main, and main's own cleanup removes what the put made`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, endsWithMain = true, report = { line: String -> synchronized(reports) { reports += line } })
        val removal: KeyItemRemoval = cleanup.arm(MacKeyItemRemoval(keychain, item))
        val store: HeldPutStore = HeldPutStore(keychain, item, directory)
        val mainAtEnd: CountDownLatch = CountDownLatch(1)
        val mainMayEnd: CountDownLatch = CountDownLatch(1)
        val run: CompletableFuture<Throwable?> = CompletableFuture.supplyAsync {
            val failure: Throwable? = try {
                runBlocking { runSecureRoundTrip(store, openSecond = { store }, removal = removal, stop = cleanup.stop) }
                null
            } catch (thrown: Throwable) {
                thrown
            }
            // main: its finally deletes the directory, then (after the verdict) it says the run is over.
            cleanup.deleteDirectory()
            mainAtEnd.countDown()
            check(mainMayEnd.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)) { "main was never let end" }
            cleanup.markRunFinished()
            failure
        }
        val hooks: List<CompletableFuture<Void>>
        try {
            assertTrue(store.putEntered.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the first put is in flight")
            hooks = listOf(CompletableFuture.runAsync { cleanup.runNow() }, CompletableFuture.runAsync { cleanup.runNow() })
            // Both hooks have begun once a step refuses: wait for the flag without sleeping (bounded).
            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(HARD_BOUND_SECONDS)
            while (true) {
                val refused: Boolean = try {
                    cleanup.stop.check()
                    false
                } catch (_: SmokeFailure) {
                    true
                }
                if (refused) break
                check(System.nanoTime() < deadline) { "the hook never set its flag" }
                Thread.yield()
            }
            store.putRelease.countDown()
            // The run's body ends at its next step, its cleanup (key item included) and main's directory work run; main has not said the
            // run is over yet, so no hook may end. A hook that ends now does so within the grace; one that waits is still waiting after it.
            assertTrue(mainAtEnd.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the run's own cleanup and main's directory work ran")
            val endedEarly: Boolean = try {
                CompletableFuture.anyOf(*hooks.toTypedArray()).get(GRACE_MILLIS, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                false
            }
            assertFalse(endedEarly, "a hook ended before main said the run is over (the JVM would halt under main's verdict): reports=$reports")
            mainMayEnd.countDown()
            hooks.forEach { it.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS) }
        } finally {
            store.putRelease.countDown()
            mainMayEnd.countDown()
        }
        val outcome: Throwable? = run.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        assertTrue(outcome is SmokeFailure && STOPPED_FROM_OUTSIDE in outcome.message.orEmpty(), "the run ends stopped from outside, got $outcome")
        assertTrue(keychain.items.isEmpty(), "the item the put made is left: ${keychain.items.keys}; calls=${keychain.calls}")
        assertEquals(1, keychain.calls.count { it == "delete" }, "the run's removal ran once and no hook removed: ${keychain.calls}")
        assertEquals(emptyList(), synchronized(reports) { reports.toList() })
        assertFalse(directory.exists(), "the directory the put recreated is left")
    }

    /**
     * Defect class: hang / silent leak. The run's own removal is wedged inside the binding (holds the binding lock) when the hook fires; the
     * run never comes back. The hook must give up within its bounds and SAY that the item may be left, never return silently and never hang.
     * Mutation by reading: an unbounded wait anywhere on the hook path (runEnded.get(), outcome.get() or the lock) turns it RED by the hard
     * bound; swallowing the hook's failure turns the report assertion RED.
     */
    @Test
    fun `a hook that fires while the run's removal is wedged in the binding gives up within its bounds and reports the item may be left`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val inside: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(
            directory,
            boundMillis = SHORT_MILLIS,
            runWaitMillis = SHORT_MILLIS,
            report = { line: String -> synchronized(reports) { reports += line } },
        )
        val removal: KeyItemRemoval = cleanup.arm(
            object : KeyItemRemoval {
                override fun remove(expectPresent: Boolean) {
                    inside.countDown()
                    release.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
                }
            },
        )
        val runRemoval: CompletableFuture<Void> = CompletableFuture.runAsync { removal.remove(expectPresent = true) }
        try {
            assertTrue(inside.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS), "control: the run's removal is inside the binding")
            CompletableFuture.runAsync { cleanup.runNow() }.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        } finally {
            release.countDown()
        }
        runRemoval.get(HARD_BOUND_SECONDS, TimeUnit.SECONDS)
        val lines: List<String> = synchronized(reports) { reports.toList() }
        assertEquals(1, lines.size, "one report: $lines")
        assertTrue("may be left" in lines.single(), "the report says the item may be left: $lines")
        assertFalse(directory.exists(), "the hook deletes the directory in its place")
    }

    /** A secure store whose first `put` makes the key item and the store file (recreating the directory) only when [putRelease] opens. */
    private class HeldPutStore(
        private val keychain: FakeKeychain,
        private val itemOfTheStore: MacKeyItem,
        private val directory: File,
    ) : SecureKeyValueStorage {
        private val data: MutableMap<String, String> = mutableMapOf()
        val putEntered: CountDownLatch = CountDownLatch(1)
        val putRelease: CountDownLatch = CountDownLatch(1)
        
        @Volatile
        private var itemWasMade: Boolean = false

        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_USER_SCOPED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)

        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            if (!itemWasMade) {
                putEntered.countDown()
                check(putRelease.await(HARD_BOUND_SECONDS, TimeUnit.SECONDS)) { "the put was never released" }
                synchronized(keychain) { keychain.items[itemOfTheStore.service to itemOfTheStore.account] = ByteArray(32) { 1 } }
                directory.mkdirs()
                File(directory, "store.bin").writeText("ciphertext")
                itemWasMade = true
            }
            data[key] = value
            return StorageResult.Success(Unit)
        }

        override suspend fun get(key: String): StorageResult<String?> = StorageResult.Success(data[key])

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

        /** The hook's bounds in the wedged test (give-up bounds of the code under test, not timing assertions). */
        const val SHORT_MILLIS: Long = 200L

        /** How long the test gives a hook that wrongly ends early to do so (a give-up bound, not a timing assertion). */
        const val GRACE_MILLIS: Long = 2_000L
    }
}
