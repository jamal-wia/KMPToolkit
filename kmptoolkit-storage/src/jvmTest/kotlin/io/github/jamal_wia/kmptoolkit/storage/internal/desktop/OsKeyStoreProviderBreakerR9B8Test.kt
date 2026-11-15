package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Breaker round 8 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-8), against task/sto-desktop 74357cee: the
 * round-10 fixes as they reach the stores.
 *
 * - B7-M1 (a key-check that a provider fails on is "key lost" for a file with ZERO entries). 05 and the KDoc of
 *   `OsKeyStoreEngine.verifies` sell the cost as "a transient token error on an empty store then costs only a harmless key
 *   replacement". Is it harmless for ANOTHER process that holds the old key in its cache? Does a corrupted entry table ever read as
 *   zero entries (the count is not authenticated)? Is `clear()` then `put` a way out on Windows too, and is the app-key text honest?
 * - A8-M1 (the withdrawal at the instant of the cancellation) as it reaches a store: a store call whose native call is queued and
 *   whose own dispatcher is busy when it is cancelled.
 *
 * The stand-in provider [B8TokenGcmProvider] serves AES-GCM through SunJCE; it attaches a provider cause to real tag failures (the
 * `P11AEADCipher` shape) when asked, and fails the next N decryptions with a token error (a transient PKCS#11 failure). It is
 * inserted first and removed in `finally`. Fake binding only, fixed inputs, latches with bounds, no sleeps.
 */
class OsKeyStoreProviderBreakerR9B8Test {

    private fun <T> withTokenProvider(causeOnRealTagFailure: Boolean, block: () -> T): T {
        B8TokenGcmSpi.causeOnRealTagFailure = causeOnRealTagFailure
        B8TokenGcmSpi.failNextDecrypts.set(0)
        check(Security.insertProviderAt(B8TokenGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(B8TokenGcmProvider.NAME)
            B8TokenGcmSpi.failNextDecrypts.set(0)
            B8TokenGcmSpi.causeOnRealTagFailure = true
        }
    }

    /** The native call that puts a NEW key of an OS-key store into the OS, per OS. */
    private fun createCall(os: DesktopOs): String = when (os) {
        DesktopOs.MACOS -> "addGenericPassword"
        DesktopOs.LINUX -> "store"
        DesktopOs.WINDOWS -> "protect"
    }

    private fun quarantined(directory: File): List<String> = directory.list().orEmpty().filter { ".undecryptable-" in it }

    // ---- B7-M1: the "harmless key replacement" seen from another process ----

    /**
     * Two processes (two key caches) over one EMPTY OS-key store. Process B made it (`ensureKey`) and holds its key K1 in its cache.
     * Process A's first `put` meets ONE transient token error on the key-check (the B7-M1 rule reads that as "key lost" for a file
     * with zero entries) and so replaces the key by K2 and writes its entry. B then uses the store: its cached K1 no longer opens the
     * key-check. Under SunJCE-like tag failures (control) that is a mismatch: B drops K1, fetches K2 and reads A's entry. Under the
     * PKCS#11 shape a real tag failure carries a cause, so B's mismatch on a file that now HAS an entry is a provider failure, and
     * the cache entry is never dropped: every call of B answers `OperationFailed` for the rest of its process, although the store,
     * its item and its file are healthy. 05 calls this cost "only a harmless key replacement". Red on: B not reading A's entry or
     * not writing, after the replacement, on any of the three OSes.
     */
    @Test
    fun `a key replaced because of a transient token error never locks another process with the old key out`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val broken: MutableList<String> = mutableListOf()
            for (cause: Boolean in listOf(false, true)) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    val label = "$os, real tag failures ${if (cause) "WITH a cause (PKCS#11 shape)" else "without a cause (control)"}"
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val cacheB = OsKeyCache()
                        val processB: EncryptedKeyValueStorage = rig.store(cache = cacheB)
                        assertEquals(StorageResult.Success(Unit), processB.ensureKey(), "$label: control, B makes the empty store")
                        assertEquals(1, cacheB.size, "$label: control, B holds the key in its cache")
                        val processA: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        val creates: Int = rig.fake.count(createCall(os))
                        withTokenProvider(causeOnRealTagFailure = cause) {
                            runBlocking {
                                val injected: Int = B8TokenGcmSpi.injected.get()
                                B8TokenGcmSpi.failNextDecrypts.set(1)
                                assertEquals(StorageResult.Success(Unit), processA.put("x", "1"), "$label: A's put after the transient error")
                                assertEquals(injected + 1, B8TokenGcmSpi.injected.get(), "$label: control, the transient token error was served")
                                assertEquals(creates + 1, rig.fake.count(createCall(os)), "$label: control, the key was replaced (the documented cost)")
                                val answers: List<StorageResult<*>> = listOf(processB.get("x"), processB.get("x"), processB.put("y", "2"), processB.ensureKey())
                                val expected: List<StorageResult<*>> = listOf(
                                    StorageResult.Success("1"), StorageResult.Success("1"), StorageResult.Success(Unit), StorageResult.Success(Unit),
                                )
                                if (answers != expected) broken += "$label: B (warm, old key) answered $answers"
                                if (!cause) assertEquals(expected, answers, "$label: control, B recovers by fetching the new key")
                            }
                        }
                        assertEquals(emptyList(), quarantined(rig.directory), "$label: nothing quarantined")
                    }
                }
            }
            assertEquals(
                emptyList(),
                broken,
                "05: 'a transient token error on an empty store costs only a harmless key replacement'; another process that holds the " +
                    "old key must not be locked out of a healthy store for the rest of its life",
            )
        }
        Unit
    }

    /**
     * The same process: a warm cache on an empty store, ONE transient token error on the cached key's check. The cached key is
     * dropped and fetched again, the fetched key verifies, and NO key is replaced (no create call): a single error must not cost a
     * replacement when the item still answers. Control: the error was served.
     */
    @Test
    fun `one transient token error on a warm empty store refetches the key and replaces nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val store: EncryptedKeyValueStorage = rig.store()
                    assertEquals(StorageResult.Success(Unit), store.ensureKey(), "$os: control, the empty store exists")
                    val creates: Int = rig.fake.count(createCall(os))
                    val header: ByteArray = rig.dataFile().readBytes()
                    withTokenProvider(causeOnRealTagFailure = true) {
                        runBlocking {
                            val injected: Int = B8TokenGcmSpi.injected.get()
                            B8TokenGcmSpi.failNextDecrypts.set(1)
                            assertEquals(StorageResult.Success(Unit), store.put("x", "1"), "$os: put")
                            assertEquals(injected + 1, B8TokenGcmSpi.injected.get(), "$os: control, the token error was served")
                        }
                    }
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: one transient error replaced the key of a store whose item answers")
                    val after: ParsedStoreFile = (StoreClassifier.classify(rig.dataFile().readBytes(), StoreKind.entries.toSet()) as FileState.Parsed).file
                    val before: ParsedStoreFile = (StoreClassifier.classify(header, StoreKind.entries.toSet()) as FileState.Parsed).file
                    assertContentEquals(before.keyCheck, after.keyCheck, "$os: the key-check changed")
                    assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("x"), "$os: a cold instance reads it")
                }
            }
        }
        Unit
    }

    // ---- B7-M1: can a file with entries ever count as "zero entries"? ----

    /**
     * The entry count is not authenticated (it follows the key-check). Every SINGLE fault of a store file with two entries, under a
     * provider that fails EVERY decryption (the rule's trigger), must never read as "zero entries": the count changed to each value
     * from 0 to 3 with the entries kept, and the file truncated at every length from the end of the count to one byte short. For
     * each: `ensureKey` answers `Undecryptable(null)`, `get` answers `Undecryptable(null)` (never `Success(null)`), the file is
     * byte-identical, the item is untouched and no key is created. Red on: a fault that parses with zero entries (the key would be
     * replaced and both entries lost).
     */
    @Test
    fun `a corrupted entry count or a truncated entry table never reads as a store with zero entries`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("a", "1"), "$os: control")
                    assertEquals(StorageResult.Success(Unit), rig.store().put("b", "2"), "$os: control")
                    val healthy: ByteArray = rig.dataFile().readBytes()
                    val parsed: ParsedStoreFile = (StoreClassifier.classify(healthy, StoreKind.entries.toSet()) as FileState.Parsed).file
                    val countAt: Int = parsed.headerPrefix.size + SecureFileFormat.KEY_CHECK_BYTES
                    assertEquals(2, healthy[countAt + 3].toInt(), "$os: control, the count field is where the format says")
                    val faults: MutableList<Pair<String, ByteArray>> = mutableListOf()
                    for (count: Int in 0..3) {
                        if (count == 2) continue
                        faults += "count $count, entries kept" to healthy.copyOf().also { it[countAt + 3] = count.toByte() }
                    }
                    for (length: Int in countAt + 4 until healthy.size) faults += "truncated to $length" to healthy.copyOf(length)
                    val items: Int = rig.fake.macItems.size + rig.fake.secretItems.size
                    val creates: Int = rig.fake.count(createCall(os))
                    withTokenProvider(causeOnRealTagFailure = true) {
                        runBlocking {
                            for ((fault: String, bytes: ByteArray) in faults) {
                                rig.dataFile().writeBytes(bytes)
                                B8TokenGcmSpi.failNextDecrypts.set(Int.MAX_VALUE)
                                val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                                assertEquals(StorageResult.Failure(StorageError.Undecryptable(null)), store.ensureKey(), "$os, $fault: ensureKey")
                                assertEquals(StorageResult.Failure(StorageError.Undecryptable(null)), store.get("a"), "$os, $fault: get")
                                assertContentEquals(bytes, rig.dataFile().readBytes(), "$os, $fault: the file changed")
                            }
                        }
                    }
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: a key was created for a corrupted file")
                    assertEquals(items, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: the item changed")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: something was quarantined")
                }
            }
        }
        Unit
    }

    // ---- B7-M1: the way out on Windows, and the app-key text ----

    /**
     * Windows (not covered by the executor's round-10 tests): a DPAPI header whose blob opens to ANOTHER key (one byte of the
     * protected key changed), with an entry. Under the PKCS#11 shape: `get` answers `OperationFailed` (control: the key-check path,
     * not a blob DPAPI refuses), then `clear()`, `put`, `ensureKey` and a cold `get` work, as 05 says for every OS-key store.
     */
    @Test
    fun `clear then put is a way out of a lost key on Windows under the PKCS11 shape`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "control: the store exists")
                val bytes: ByteArray = rig.dataFile().readBytes()
                val bodyLength: Int = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                // The last byte of the header body is the last byte of the protected key (the blob ends the body).
                bytes[8 + bodyLength - 1] = (bytes[8 + bodyLength - 1].toInt() xor 0x01).toByte()
                rig.dataFile().writeBytes(bytes)
                val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                withTokenProvider(causeOnRealTagFailure = true) {
                    runBlocking {
                        val get: StorageResult<String?> = store.get("k")
                        val error: StorageError = assertIs<StorageResult.Failure>(get, "control: $get").error
                        assertEquals(StorageOperation.GET, assertIs<StorageError.OperationFailed>(error, "control: the key-check path, $error").operation)
                        assertEquals(StorageResult.Success(Unit), store.clear(), "clear")
                        assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "put after clear")
                        assertEquals(StorageResult.Success(Unit), store.ensureKey(), "ensureKey")
                        assertEquals(StorageResult.Success("v2"), rig.store(cache = OsKeyCache()).get("k2"), "a cold instance reads it")
                    }
                }
                assertEquals(emptyList(), quarantined(rig.directory), "nothing quarantined (the file was emptied by clear)")
            }
        }
        Unit
    }

    /**
     * The app-key text of 05 (round 10): under such a provider an app-key store opened with a key that cannot open it answers
     * `OperationFailed` (never `AccessDenied`: the provider failed), its `clear()` answers `OperationFailed` too and changes nothing,
     * and the documented way out, deleting the store's file, works: a `put` with the new key then creates a working store.
     */
    @Test
    fun `an app-key store under the PKCS11 shape has the documented way out and clear changes nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val name = "b8app"
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                val original = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 6 } }, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), original.put("k", "v"), "control")
                val before: ByteArray = file.readBytes()
                val other = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 7 } }, Dispatchers.IO, CrossProcessLock())
                withTokenProvider(causeOnRealTagFailure = true) {
                    runBlocking {
                        for ((label: String, answer: StorageResult<*>) in listOf("get" to other.get("k"), "put" to other.put("k", "w"), "clear" to other.clear())) {
                            val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
                            assertIs<StorageError.OperationFailed>(error, "$label: 05 says OperationFailed under such a provider, was $error")
                        }
                        assertContentEquals(before, file.readBytes(), "a failed call changed the file")
                        assertTrue(file.delete(), "control: the documented way out, the store's file is deleted")
                        assertEquals(StorageResult.Success(Unit), other.put("k", "w"), "a put with the new key after the file was deleted")
                        assertEquals(StorageResult.Success("w"), other.get("k"), "and reads back")
                    }
                }
            }
        }
        Unit
    }

    // ---- A8-M1 through a store: cancelled while queued, own dispatcher busy ----

    /**
     * A macOS store's first `put` (dialogs allowed: no limit) reaches its native install while the shared native thread is busy
     * with another store's fetch, so the install is QUEUED. Its OWN dispatcher (one thread) is then made busy, and the call is
     * cancelled. The call must be withdrawn at that instant (05: "in the thread that cancels, not when the caller's own dispatcher
     * gets round to resuming the caller"): the queue is down to the running fetch at once, the native thread, once free, serves a
     * later call and never the cancelled one (no keychain call of the cancelled store is recorded), and when the dispatcher is free
     * again the rollback leaves no file and no item. Control: the install was queued (two pending calls).
     */
    @Test
    fun `a store call cancelled while its native call is queued and its own dispatcher is busy never makes that call`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val held: ExecutorService = Executors.newSingleThreadExecutor()
            val heldDispatcher: CoroutineDispatcher = held.asCoroutineDispatcher()
            try {
                SecureTestRig(DesktopOs.MACOS).use { busyRig: SecureTestRig ->
                    SecureTestRig(DesktopOs.MACOS, dispatcher = heldDispatcher).use { victimRig: SecureTestRig ->
                        val ui = DesktopSecureStorageOptions(allowUserInteraction = true)
                        assertEquals(StorageResult.Success(Unit), busyRig.store(options = ui).put("m", "1"), "control: the other store exists")
                        val busy = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val armed = AtomicBoolean(true)
                        busyRig.fake.before = { name: String ->
                            if (name == "copyGenericPassword" && armed.compareAndSet(true, false)) {
                                busy.countDown()
                                check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                            }
                        }
                        val blocker = async(Dispatchers.Default) { busyRig.store(options = ui, cache = OsKeyCache()).get("m") }
                        val victim: EncryptedKeyValueStorage = victimRig.store(options = ui, executor = busyRig.executor)
                        val holding = CountDownLatch(1)
                        val unhold = CountDownLatch(1)
                        // The put runs outside this test's scope, so a defect that strands it fails the bounded join below instead of
                        // keeping runBlocking waiting for a child.
                        val outside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        try {
                            val put: Deferred<StorageResult<Unit>> = try {
                                check(busy.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the fetch never started" }
                                val put: Deferred<StorageResult<Unit>> = outside.async { victim.put("v", "1") }
                                while (busyRig.executor.pendingCalls < 2) yield()
                                assertEquals(2, busyRig.executor.pendingCalls, "control: the fetch runs and the store's native call is queued")
                                assertEquals(emptyList(), victimRig.fake.callNames, "control: the cancelled store made no keychain call yet")
                                held.execute {
                                    holding.countDown()
                                    unhold.await(HANG_SECONDS, TimeUnit.SECONDS)
                                }
                                check(holding.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the store's dispatcher was never made busy" }
                                put.cancel()
                                assertEquals(1, busyRig.executor.pendingCalls, "the queued call was not withdrawn at the cancellation")
                                put
                            } finally {
                                release.countDown()
                            }
                            try {
                                assertEquals(StorageResult.Success("1"), blocker.await(), "control: the blocking fetch completes")
                                // A later call through the same native thread: it runs only after the thread passed the withdrawn slot.
                                assertEquals(StorageResult.Success("1"), busyRig.store(options = ui, cache = OsKeyCache()).get("m"), "control: a later native call is served")
                                assertEquals(emptyList(), victimRig.fake.callNames, "a withdrawn keychain call ran after its caller was cancelled")
                            } finally {
                                unhold.countDown()
                            }
                            assertTrue(withTimeoutOrNull(HANG_SECONDS.seconds) { put.join() } != null, "the cancelled put never finished")
                            assertTrue(put.isCancelled, "control: the put was cancelled")
                        } finally {
                            unhold.countDown()
                            outside.cancel()
                        }
                        assertEquals(0, victimRig.fake.macItems.size, "the cancelled create left an item")
                        assertFalse(victimRig.dataFile().exists(), "the cancelled create left a file")
                        assertEquals(StorageResult.Success(Unit), victim.put("v", "2"), "the same store works afterwards")
                        assertEquals(StorageResult.Success("2"), victimRig.store(options = ui, executor = busyRig.executor, cache = OsKeyCache()).get("v"), "and reads back cold")
                    }
                }
            } finally {
                held.shutdownNow()
            }
        }
        Unit
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 120.seconds
    }
}

/** Serves `AES/GCM/NoPadding` through [B8TokenGcmSpi]. */
class B8TokenGcmProvider : Provider(NAME, "1.0", "breaker B8: AES-GCM with PKCS#11-shaped tag failures and transient token errors") {
    init {
        put("Cipher.AES/GCM/NoPadding", B8TokenGcmSpi::class.java.name)
    }

    companion object {
        const val NAME: String = "KmpToolkitBreakerB8TokenGcm"
    }
}

/**
 * Delegates to SunJCE's AES-GCM. With [causeOnRealTagFailure] a REAL tag failure is reported the way `P11AEADCipher` reports every
 * decryption failure (an `AEADBadTagException` with the provider's exception as its cause). [failNextDecrypts] makes that many next
 * decryptions fail the same way although the key is right: a transient token error.
 */
class B8TokenGcmSpi : CipherSpi() {
    private var cipher: Cipher? = null
    private var decrypting: Boolean = false

    private fun target(): Cipher = checkNotNull(cipher) { "not initialised" }

    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = target().getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = cipher?.iv
    override fun engineGetParameters(): AlgorithmParameters? = cipher?.parameters

    private fun open(opmode: Int): Cipher {
        decrypting = opmode == Cipher.DECRYPT_MODE
        return Cipher.getInstance("AES/GCM/NoPadding", checkNotNull(Security.getProvider("SunJCE")) { "control: SunJCE exists" }).also { cipher = it }
    }

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        open(opmode).init(opmode, key, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        open(opmode).init(opmode, key, params, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
        open(opmode).init(opmode, key, params, random)
    }

    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {
        target().updateAAD(src, offset, len)
    }

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = target().update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        target().update(input, inputOffset, inputLen, output, outputOffset)

    private fun tokenError(): AEADBadTagException =
        AEADBadTagException("CKR_ENCRYPTED_DATA_INVALID (breaker B8 stand-in)").apply { initCause(ProviderException("token: data invalid")) }

    private inline fun <T> served(block: () -> T): T {
        if (decrypting && failNextDecrypts.getAndUpdate { left: Int -> if (left > 0) left - 1 else 0 } > 0) {
            injected.incrementAndGet()
            throw tokenError()
        }
        return try {
            block()
        } catch (mismatch: AEADBadTagException) {
            if (!causeOnRealTagFailure) throw mismatch
            throw tokenError()
        }
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = served { target().doFinal(input, inputOffset, inputLen) }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        served { target().doFinal(input, inputOffset, inputLen, output, outputOffset) }

    companion object {
        /** How many next decryptions fail with a token error although the key is right. */
        val failNextDecrypts: AtomicInteger = AtomicInteger()

        /** How many token errors [failNextDecrypts] served. */
        val injected: AtomicInteger = AtomicInteger()

        @Volatile
        var causeOnRealTagFailure: Boolean = true
    }
}
