package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 8 (the secret-creation audit asked for by breaker finding B5-m2): a copy of key material that is
 * handed to a native call is wiped on EVERY path, including the ones where the native block never runs.
 *
 * `NativeCaller.callOwning` is the one place that owns this rule: the native thread wipes the copy in a `finally` when its
 * block ends (a call still running keeps reading a live array until then), and the caller wipes it when the block can no
 * longer start (it was withdrawn from the queue, the executor was closed). Before this round the copy was wiped only inside
 * the block, so a copy whose block never ran stayed on the heap. Every test holds the array it handed over, with a control
 * assertion that it was live while the block could still use it. Latches only, with hard bounds; no sleeps.
 */
class NativeCallerOwnershipRound8Test {

    private fun live(bytes: ByteArray): Boolean = bytes.any { it != 0.toByte() }

    private fun secret(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    /** A call that runs to the end: the block sees the live key, the array is zero afterwards, and the answer is the block's. */
    @Test
    fun `a call that runs sees the live key and leaves a wiped array`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                val owned: ByteArray = secret()
                var seenLive = false
                val outcome: NativeOutcome<Int?> = NativeCaller(executor, allowUi = true).callOwning(owned) { bytes: ByteArray ->
                    seenLive = bytes === owned && live(bytes)
                    7
                }
                assertTrue(seenLive, "control: the block received the live array")
                assertEquals(7, assertIs<NativeOutcome.Completed<Int?>>(outcome).value)
                assertTrue(!live(owned), "the array handed to the native call must be wiped once the call returned")
                assertEquals(0, executor.pendingCalls)
            }
        }
        Unit
    }

    /** A block that throws wipes the array too (the `finally` of the native thread). */
    @Test
    fun `a block that throws leaves a wiped array`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                val owned: ByteArray = secret()
                val outcome: NativeOutcome<Int?> = NativeCaller(executor, allowUi = true).callOwning<Int>(owned) { _: ByteArray ->
                    throw IllegalStateException("the native call failed")
                }
                assertIs<NativeOutcome.Threw>(outcome)
                assertTrue(!live(owned), "the array must be wiped after a native call that threw")
            }
        }
        Unit
    }

    /**
     * The caller leaves while its call is QUEUED behind a busy native thread: the call is withdrawn and its block never runs, so
     * only the caller can wipe the copy. Control: the array is live while the call is queued and the block never ran.
     */
    @Test
    fun `a call withdrawn from the queue still gets its array wiped`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                val busy = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocker = async(Dispatchers.Default) {
                    executor.call(null) {
                        busy.countDown()
                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the busy call" }
                    }
                }
                try {
                    check(busy.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the busy call never started" }
                    val owned: ByteArray = secret()
                    val ran = AtomicInteger()
                    // UNDISPATCHED: the body runs up to its first suspension, which is after the call was queued.
                    val queued = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        NativeCaller(executor, allowUi = true).callOwning(owned) { _: ByteArray ->
                            ran.incrementAndGet()
                        }
                    }
                    assertEquals(2, executor.pendingCalls, "control: the owning call is queued behind the busy one")
                    assertTrue(live(owned), "control: the array is live while its call is queued")
                    queued.cancel()
                    queued.join()
                    assertTrue(!live(owned), "a withdrawn call never runs its block, so the caller must wipe the copy")
                    assertEquals(0, ran.get(), "the withdrawn block never ran")
                } finally {
                    release.countDown()
                }
                blocker.await()
                assertEquals(0, executor.pendingCalls, "nothing is left queued")
            }
        }
        Unit
    }

    /**
     * The caller leaves while its call RUNS: the native thread still reads the array, so it must stay live until the block
     * returns (wiping under a reader would corrupt the call), and then it is wiped by the thread that read it.
     */
    @Test
    fun `a running call keeps its array until the block returns and then wipes it`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                val inside = CountDownLatch(1)
                val release = CountDownLatch(1)
                val owned: ByteArray = secret()
                var liveInsideAfterLeave = false
                val running = async(Dispatchers.Default) {
                    NativeCaller(executor, allowUi = true).callOwning(owned) { bytes: ByteArray ->
                        inside.countDown()
                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the native call" }
                        liveInsideAfterLeave = live(bytes)
                    }
                }
                try {
                    check(inside.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the native call never started" }
                    assertTrue(live(owned), "control: the array is live while the call runs")
                    running.cancel()
                    running.join()
                    assertTrue(live(owned), "the caller left, but the native call still reads the array: it must not be wiped yet")
                } finally {
                    release.countDown()
                }
                // The native thread wipes it after the block; wait for the thread to be idle (the call counts as pending until then).
                val idle = CountDownLatch(1)
                executor.call(null) { idle.countDown() }
                assertTrue(idle.await(HANG_SECONDS, TimeUnit.SECONDS), "the executor went idle")
                assertTrue(liveInsideAfterLeave, "the block saw its live array to the end")
                assertTrue(!live(owned), "the native thread wipes the array after the block returned")
            }
        }
        Unit
    }

    /** A closed executor never runs the block: the caller wipes the copy, and the answer is a typed outcome. */
    @Test
    fun `a call on a closed executor gets its array wiped`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val executor = NativeExecutor()
            executor.close()
            val owned: ByteArray = secret()
            assertTrue(live(owned), "control: the array is live before the call")
            val outcome: NativeOutcome<Int?> = NativeCaller(executor, allowUi = true).callOwning(owned) { _: ByteArray -> 1 }
            assertIs<NativeOutcome.Threw>(outcome)
            assertTrue(!live(owned), "a refused call never runs its block, so the caller must wipe the copy")
        }
        Unit
    }

    // ---- the backends, through the fake binding ----

    /** A macOS install whose caller is cancelled while its first native call (the delete) runs: the owned copy is wiped. */
    @Test
    fun `a macOS install cancelled before its add call hands back a wiped copy`() = runBlocking {
        withTimeout(HANG_BOUND) {
            NativeExecutor().use { executor ->
                val fake = FakeNativeKeyStoreBinding()
                val directory: File = Files.createTempDirectory("kmptoolkit-ownership").toFile()
                try {
                    val identity: StoreIdentity = StoreIdentity.of("ownership", directory)
                    val backend = MacBackend(fake.macOs(), label = null, caller = NativeCaller(executor, allowUi = true))
                    val inside = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    fake.before = { name: String ->
                        if (name == "deleteGenericPassword") {
                            inside.countDown()
                            check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the delete call" }
                        }
                    }
                    val owned: ByteArray = secret()
                    val install = async(Dispatchers.Default) {
                        backend.install(identity, owned, StorageOperation.PUT, "k")
                    }
                    try {
                        check(inside.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the delete call never started" }
                        assertTrue(live(owned), "control: the copy is live while the delete runs")
                        install.cancel()
                        install.join()
                        assertTrue(!live(owned), "an install cancelled before its add call must wipe the copy it owns")
                    } finally {
                        release.countDown()
                    }
                    assertEquals(0, fake.count("addGenericPassword"), "the add call never ran")
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
        Unit
    }

    /**
     * The engine hands `install` a COPY of the plan's key: the item the key store received holds the live key (the add and
     * store calls saw it), and the plan's own key, which `commit` puts into the cache, is still the live key afterwards (an
     * install that was given the plan's array itself would wipe it and cache zeros). A cold instance opens the store with the
     * key that was installed.
     */
    @Test
    fun `the engine installs a copy and keeps the plan's own key live for the cache`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the put works")
                    val cached: ByteArray = rig.cache.get(StoreIdentity.of(SecureTestRig.NAME, rig.directory)) ?: error("$os: the key was not cached")
                    assertTrue(live(cached), "$os: the key put into the cache is the live key (an install that wiped the plan's array would cache zeros)")
                    if (os == DesktopOs.MACOS) assertTrue(rig.fake.macItems.values.single().let(::live), "macOS: the item holds the live key")
                    if (os == DesktopOs.LINUX) assertTrue(rig.fake.secretItems.values.single().secret.let(::live), "Linux: the item holds the key")
                    assertEquals(StorageResult.Success("v"), rig.store(cache = OsKeyCache()).get("k"), "$os: a cold instance opens the store with the installed key")
                }
            }
        }
        Unit
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 120.seconds
    }
}
