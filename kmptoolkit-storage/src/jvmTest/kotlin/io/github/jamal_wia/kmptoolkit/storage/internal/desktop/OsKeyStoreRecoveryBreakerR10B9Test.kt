package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * Breaker round 9 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-9), against task/sto-desktop 87390e1f: the
 * round-11 fix of B8-M1 (D-P16) as implemented, and the phase A changes of round 11 as they reach the stores.
 *
 * D-P16 says: the key cache is never trusted without its key-check; after a provider failure on the cached key of a file WITH
 * entries the item is asked for its key only when a pure round trip proves the provider still works; the cache is replaced only by
 * a fetched key that opens this very file; a provider that fails for everything makes no fetch; an equal key, a missing item or a
 * fetched key that does not open the file keeps the failure and the cache; nothing is written, quarantined or created for it; a file
 * that keeps changing can end in `Unavailable`. 05 adds: another process that holds a replaced key recovers at the cost of one
 * fetch and no lock-out. Each test below attacks one of those sentences with PKCS#11-shaped failures, two key caches standing in for
 * two processes (the cache is the only per-process state involved), latches with hang bounds, and fixed seeds.
 *
 * The stand-in provider [B9ScriptGcmProvider] serves AES-GCM through SunJCE, reports real tag failures the `P11AEADCipher` way
 * (with a provider cause) when asked, and fails or interrupts chosen decryptions by their index since it was armed. It is inserted
 * first and removed in `finally`; like every provider test of the module it relies on Gradle running the methods one by one.
 *
 * Decryption indices of one call of a process B whose cached key K1 is stale on a file with entries under K2 (fetch = native):
 * 1 = K1 against the key-check, 2 = the round trip with K1, fetch, 3 = K1 again (next round), 4 = the fetched K2, 5 = the entry.
 */
class OsKeyStoreRecoveryBreakerR10B9Test {

    private fun <T> withScriptProvider(causeOnRealTagFailure: Boolean = true, block: () -> T): T {
        B9ScriptGcmSpi.causeOnRealTagFailure = causeOnRealTagFailure
        B9ScriptGcmSpi.arm()
        check(Security.insertProviderAt(B9ScriptGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(B9ScriptGcmProvider.NAME)
            B9ScriptGcmSpi.arm()
            B9ScriptGcmSpi.causeOnRealTagFailure = true
        }
    }

    private fun fetchCall(os: DesktopOs): String = when (os) {
        DesktopOs.MACOS -> "copyGenericPassword"
        DesktopOs.LINUX -> "lookup"
        DesktopOs.WINDOWS -> "unprotect"
    }

    /**
     * Native calls that create, replace or delete an OS item (macOS, Linux). A Windows key lives in the file's header (its `protect`
     * calls include the 1-byte round trip of plan 4.6), so on Windows the byte-identical file and [itemKey] are the proof instead.
     */
    private fun keyWrites(rig: SecureTestRig): Int =
        listOf("addGenericPassword", "deleteGenericPassword", "store", "clear").sumOf { rig.fake.count(it) }

    private fun quarantined(directory: File): List<String> = directory.list().orEmpty().filter { ".undecryptable-" in it }

    private fun identity(rig: SecureTestRig): StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)

    /** The key the OS item holds now, as a cold instance resolves it (works for every OS, the DPAPI header included). */
    private suspend fun itemKey(rig: SecureTestRig): ByteArray {
        val cold = OsKeyCache()
        assertEquals(StorageResult.Success(Unit), rig.store(cache = cold).ensureKey(), "control: a cold instance resolves the key")
        return checkNotNull(cold.get(identity(rig))) { "control: the cold instance cached the key" }.copyOf()
    }

    private fun assertFailedWith(answer: StorageResult<*>, operation: StorageOperation, label: String) {
        val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
        assertEquals(operation, assertIs<StorageError.OperationFailed>(error, "$label: $error").operation, "$label: operation")
    }

    /**
     * Two processes over one store: B made it (`ensureKey`, K1 cached in [Stale.cacheB]); A's first `put` met one token error on the
     * key-check of the empty file and replaced the key (K2) and wrote `a = 1`. Must be called inside [withScriptProvider].
     */
    private class Stale(val processB: EncryptedKeyValueStorage, val cacheB: OsKeyCache, val k1: ByteArray)

    private suspend fun stale(rig: SecureTestRig, label: String, storeB: (OsKeyCache) -> EncryptedKeyValueStorage = { rig.store(cache = it) }): Stale {
        val cacheB = OsKeyCache()
        val processB: EncryptedKeyValueStorage = storeB(cacheB)
        assertEquals(StorageResult.Success(Unit), processB.ensureKey(), "$label: control, B makes the empty store")
        val k1: ByteArray = checkNotNull(cacheB.get(identity(rig))) { "$label: control, B caches K1" }.copyOf()
        val writes: Int = keyWrites(rig)
        B9ScriptGcmSpi.arm(failAt = setOf(1))
        assertEquals(StorageResult.Success(Unit), rig.store(cache = OsKeyCache()).put("a", "1"), "$label: control, A's put")
        B9ScriptGcmSpi.arm()
        if (rig.os != DesktopOs.WINDOWS) assertTrue(keyWrites(rig) > writes, "$label: control, A replaced the item")
        assertFalse(itemKey(rig).contentEquals(k1), "$label: control, A replaced the key: the item holds another key than B's cache")
        return Stale(processB, cacheB, k1)
    }

    // ---- D-P16 under scripted token errors ----

    /**
     * B holds the stale K1; the store has an entry under K2. Each script injects token errors at chosen decryptions of B's next call
     * (indices in the class KDoc). Expected from D-P16 (answer, native fetches of that call): no error: success, 1 fetch; the round
     * trip failing (2): failure, NO fetch; the fetched K2 failing (4): failure, 1 fetch, cache untouched; every decryption failing:
     * failure and no fetch on five calls in a row (no fetch storm). After every script: the file is byte-identical, no key is
     * created, replaced or deleted, nothing is quarantined, B's cache holds K1 or the item's key and nothing else, and once the
     * provider heals B reads A's entry, writes, and a cold instance reads both. For `get` and for `put` (a lost-key `put` would
     * quarantine), on the three OS.
     */
    @Test
    fun `token errors anywhere in the recovery never lose data and B recovers once the provider heals`() = runBlocking {
        withTimeout(HANG_BOUND) {
            data class Script(val name: String, val failAt: Set<Int>, val failAll: Boolean, val succeeds: Boolean, val fetches: Int)
            val scripts: List<Script> = listOf(
                Script("no token error", emptySet(), false, true, 1),
                Script("token error on K1 only", setOf(1), false, true, 1),
                Script("round trip fails", setOf(2), false, false, 0),
                Script("fetched key fails", setOf(4), false, false, 1),
                Script("K1 again and fetched key fail", setOf(3, 4), false, false, 1),
                Script("every decryption fails", emptySet(), true, false, 0),
            )
            for (os: DesktopOs in DesktopOs.entries) {
                for (put: Boolean in listOf(false, true)) {
                    for (script: Script in scripts) {
                        val label = "$os, ${if (put) "put" else "get"}, ${script.name}"
                        SecureTestRig(os).use { rig: SecureTestRig ->
                            withScriptProvider {
                                runBlocking {
                                    val setup: Stale = stale(rig, label)
                                    val k2: ByteArray = itemKey(rig)
                                    val before: ByteArray = rig.dataFile().readBytes()
                                    val writes: Int = keyWrites(rig)
                                    val fetches: Int = rig.fake.count(fetchCall(os))
                                    val calls: Int = if (script.failAll) 5 else 1
                                    repeat(calls) { attempt: Int ->
                                        B9ScriptGcmSpi.arm(failAt = script.failAt, failAll = script.failAll)
                                        val answer: StorageResult<*> = if (put) setup.processB.put("b", "2") else setup.processB.get("a")
                                        val expected: StorageResult<*> = if (put) StorageResult.Success(Unit) else StorageResult.Success("1")
                                        if (script.succeeds) {
                                            assertEquals(expected, answer, "$label: answer")
                                        } else {
                                            assertFailedWith(answer, if (put) StorageOperation.PUT else StorageOperation.GET, "$label, call $attempt")
                                            assertContentEquals(before, rig.dataFile().readBytes(), "$label, call $attempt: a failed call changed the file")
                                        }
                                    }
                                    B9ScriptGcmSpi.arm()
                                    assertEquals(fetches + script.fetches * calls, rig.fake.count(fetchCall(os)), "$label: native fetches of the scripted calls")
                                    assertEquals(writes, keyWrites(rig), "$label: a key was created, replaced or deleted")
                                    assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                                    val cached: ByteArray = checkNotNull(setup.cacheB.get(identity(rig))) { "$label: B's cache lost its entry" }
                                    assertTrue(cached.contentEquals(setup.k1) || cached.contentEquals(k2), "$label: B's cache holds a key that is neither K1 nor the item's")
                                    if (!script.succeeds) assertContentEquals(setup.k1, cached, "$label: the cache was touched by a key that was not proved")
                                    // Healed: B recovers with at most one more fetch, and its writes are readable by anyone.
                                    val healed: Int = rig.fake.count(fetchCall(os))
                                    assertEquals(StorageResult.Success("1"), setup.processB.get("a"), "$label: B reads A's entry after the provider healed")
                                    assertTrue(rig.fake.count(fetchCall(os)) - healed <= 1, "$label: the recovery after healing cost more than one fetch")
                                    assertEquals(StorageResult.Success(Unit), setup.processB.put("c", "3"), "$label: B writes after the provider healed")
                                    val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                                    assertEquals(StorageResult.Success("1"), cold.get("a"), "$label: cold read of A's entry")
                                    assertEquals(StorageResult.Success("3"), cold.get("c"), "$label: cold read of B's entry")
                                    assertContentEquals(k2, itemKey(rig), "$label: the item's key changed")
                                    assertContentEquals(k2, setup.cacheB.get(identity(rig)), "$label: B's cache does not hold the item's key after recovery")
                                }
                            }
                        }
                    }
                }
            }
        }
        Unit
    }

    /**
     * A provider with SunJCE-shaped real tag failures (no cause) that ALSO has token errors: B's stale K1 is proved wrong by a clean
     * `false`, so the cache IS dropped, and then the fetched K2 meets a token error on a file with entries. That must be
     * `OperationFailed`, never "key lost": `put` must not quarantine the file or replace the key, `ensureKey` and `remove` must not
     * touch it either, and the next call (provider healed) recovers. macOS, Linux, Windows.
     */
    @Test
    fun `a dropped cache whose fetched key meets a token error never quarantines or replaces the key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in DesktopOs.entries) {
                for (operation: StorageOperation in listOf(StorageOperation.GET, StorageOperation.PUT, StorageOperation.ENSURE_KEY, StorageOperation.REMOVE)) {
                    val label = "$os, $operation"
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        withScriptProvider(causeOnRealTagFailure = false) {
                            runBlocking {
                                val setup: Stale = stale(rig, label)
                                val before: ByteArray = rig.dataFile().readBytes()
                                val writes: Int = keyWrites(rig)
                                val fetches: Int = rig.fake.count(fetchCall(os))
                                // 1 = K1 (clean false: dropped), fetch, 2 = the fetched K2: a token error.
                                B9ScriptGcmSpi.arm(failAt = setOf(2))
                                val answer: StorageResult<*> = when (operation) {
                                    StorageOperation.GET -> setup.processB.get("a")
                                    StorageOperation.PUT -> setup.processB.put("b", "2")
                                    StorageOperation.ENSURE_KEY -> setup.processB.ensureKey()
                                    else -> setup.processB.remove("a")
                                }
                                assertEquals(1, B9ScriptGcmSpi.injected.get(), "$label: control, the token error was served")
                                assertFailedWith(answer, operation, label)
                                B9ScriptGcmSpi.arm()
                                assertEquals(fetches + 1, rig.fake.count(fetchCall(os)), "$label: control, one fetch")
                                assertContentEquals(before, rig.dataFile().readBytes(), "$label: the file changed")
                                assertEquals(writes, keyWrites(rig), "$label: a key was created, replaced or deleted")
                                assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                                assertEquals(StorageResult.Success("1"), setup.processB.get("a"), "$label: B recovers")
                            }
                        }
                    }
                }
            }
        }
        Unit
    }

    /**
     * B's stale K1 on a file with entries while the item cannot answer: the fetch fails (macOS: interaction not allowed; Windows:
     * no logon session), or the item is gone (macOS, Linux). Every operation answers a typed failure (never `Undecryptable`, never
     * a quarantine or a new key, `put`, `ensureKey` and `remove` included), each call costs exactly one fetch (no storm inside a
     * call), B's cache keeps K1, the file is byte-identical. When the item answers again (fetch healed), B recovers.
     */
    @Test
    fun `an item that cannot answer during the recovery keeps the failure, the file and the cache`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val cases: List<Pair<DesktopOs, String>> = listOf(
                DesktopOs.MACOS to "fetch fails", DesktopOs.WINDOWS to "fetch fails", DesktopOs.MACOS to "item gone", DesktopOs.LINUX to "item gone",
            )
            for ((os: DesktopOs, case: String) in cases) {
                val label = "$os, $case"
                SecureTestRig(os).use { rig: SecureTestRig ->
                    withScriptProvider {
                        runBlocking {
                            val setup: Stale = stale(rig, label)
                            val savedMac: Map<Pair<String, String>, ByteArray> = HashMap(rig.fake.macItems)
                            val savedSecret: Map<Map<String, String>, FakeNativeKeyStoreBinding.SecretItem> = HashMap(rig.fake.secretItems)
                            when {
                                case == "item gone" -> {
                                    rig.fake.macItems.clear()
                                    rig.fake.secretItems.clear()
                                }
                                os == DesktopOs.MACOS -> rig.fake.forcedStatus["copyGenericPassword"] = ERR_SEC_INTERACTION_NOT_ALLOWED
                                else -> rig.fake.dpapiNoSession = true
                            }
                            val before: ByteArray = rig.dataFile().readBytes()
                            val writes: Int = keyWrites(rig)
                            val fetches: Int = rig.fake.count(fetchCall(os))
                            val answers: List<StorageResult<*>> = listOf(
                                setup.processB.get("a"), setup.processB.put("b", "2"), setup.processB.ensureKey(), setup.processB.remove("a"),
                            )
                            for (answer: StorageResult<*> in answers) {
                                val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
                                assertFalse(error is StorageError.Undecryptable, "$label: an item that cannot answer was read as a lost key: $error")
                            }
                            assertEquals(fetches + answers.size, rig.fake.count(fetchCall(os)), "$label: one fetch per call")
                            assertContentEquals(before, rig.dataFile().readBytes(), "$label: the file changed")
                            assertEquals(writes, keyWrites(rig), "$label: a key was created, replaced or deleted")
                            assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                            assertContentEquals(setup.k1, setup.cacheB.get(identity(rig)), "$label: B's cache changed")
                            rig.fake.forcedStatus.clear()
                            rig.fake.dpapiNoSession = false
                            rig.fake.macItems.putAll(savedMac)
                            rig.fake.secretItems.putAll(savedSecret)
                            assertEquals(StorageResult.Success("1"), setup.processB.get("a"), "$label: B recovers once the item answers")
                        }
                    }
                }
            }
        }
        Unit
    }

    /**
     * The round-10 key-lost rule for a file with ZERO entries must still hold with the round-11 change, for a stale cache under the
     * PKCS#11 shape. B holds K1; A replaced the key on the empty store by `ensureKey` (one token error), so the file stays empty under
     * K2. (1) The item answers: B's `get` drops K1, fetches K2 once, creates nothing, and caches K2. (2) The fetch fails (macOS:
     * interaction not allowed): B's `put` answers a typed failure, creates nothing, the file is byte-identical. (3) The item is gone:
     * "nothing to lose", B's `put` creates a new key and its entry reads back cold (the rule holds; no new lock-out).
     */
    @Test
    fun `the zero-entry key-lost rule still holds for a stale cache under the PKCS11 shape`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (case: String in listOf("item answers", "fetch fails", "item gone")) {
                val label = "MACOS, $case"
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    withScriptProvider {
                        runBlocking {
                            val cacheB = OsKeyCache()
                            val processB: EncryptedKeyValueStorage = rig.store(cache = cacheB)
                            assertEquals(StorageResult.Success(Unit), processB.ensureKey(), "$label: control, B makes the empty store")
                            val k1: ByteArray = checkNotNull(cacheB.get(identity(rig))).copyOf()
                            B9ScriptGcmSpi.arm(failAt = setOf(1))
                            assertEquals(StorageResult.Success(Unit), rig.store(cache = OsKeyCache()).ensureKey(), "$label: control, A's ensureKey")
                            B9ScriptGcmSpi.arm()
                            val k2: ByteArray = itemKey(rig)
                            assertFalse(k2.contentEquals(k1), "$label: control, A replaced the key")
                            val before: ByteArray = rig.dataFile().readBytes()
                            val writes: Int = keyWrites(rig)
                            val fetches: Int = rig.fake.count("copyGenericPassword")
                            when (case) {
                                "item answers" -> {
                                    assertEquals(StorageResult.Success(null), processB.get("a"), label)
                                    assertEquals(fetches + 1, rig.fake.count("copyGenericPassword"), "$label: one fetch")
                                    assertEquals(writes, keyWrites(rig), "$label: a key was created")
                                    assertContentEquals(k2, cacheB.get(identity(rig)), "$label: B's cache")
                                }
                                "fetch fails" -> {
                                    rig.fake.forcedStatus["copyGenericPassword"] = ERR_SEC_INTERACTION_NOT_ALLOWED
                                    val answer: StorageResult<Unit> = processB.put("b", "1")
                                    assertIs<StorageResult.Failure>(answer, "$label: $answer")
                                    assertEquals(writes, keyWrites(rig), "$label: a key was created for a fetch that failed")
                                    assertContentEquals(before, rig.dataFile().readBytes(), "$label: the file changed")
                                    rig.fake.forcedStatus.clear()
                                    assertEquals(StorageResult.Success(Unit), processB.put("b", "1"), "$label: B recovers")
                                }
                                else -> {
                                    rig.fake.macItems.clear()
                                    assertEquals(StorageResult.Success(Unit), processB.put("b", "1"), "$label: the empty store is repaired")
                                    assertTrue(keyWrites(rig) > writes, "$label: control, a new key was made")
                                    assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("b"), "$label: cold read")
                                }
                            }
                            assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                        }
                    }
                }
            }
        }
        Unit
    }

    // ---- races with another process during the off-lock fetch, and the livelock bound ----

    /** Wraps the fake: [onFetch] runs before every macOS/Linux fetch (with its 1-based number); macOS keys handed out are recorded. */
    private class HookBinding(private val fake: FakeNativeKeyStoreBinding) : NativeKeyStoreBinding {
        val fetches: AtomicInteger = AtomicInteger()
        val handedOut: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        @Volatile
        var onFetch: (Int) -> Unit = {}

        @Volatile
        var afterRead: (Int) -> Unit = {}

        override fun macOs(): MacKeychainCalls = object : MacKeychainCalls by fake.macOs() {
            override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
                val number: Int = fetches.incrementAndGet()
                onFetch(number)
                val raw: RawResult<ByteArray?> = fake.macOs().copyGenericPassword(service, account, allowUi)
                if (raw is RawResult.Success) raw.value?.let { handedOut += it }
                afterRead(number)
                return raw
            }
        }

        override fun windows(): DpapiCalls = fake.windows()

        override fun linux(): SecretServiceCalls = object : SecretServiceCalls by fake.linux() {
            override fun lookup(attributes: Map<String, String>, allowUi: Boolean): RawResult<SecretLookup> {
                val number: Int = fetches.incrementAndGet()
                onFetch(number)
                return fake.linux().lookup(attributes, allowUi).also { afterRead(number) }
            }
        }
    }

    private fun hooked(rig: SecureTestRig, binding: HookBinding): BindingSource = FixedBindingSource(BindingSelection.Selected(binding, rig.os, BindingKind.FAKE))

    /**
     * B recovers its stale K1 while a third process C replaces the key AGAIN during B's off-lock fetch (C empties the store, the
     * item is gone, C's `put` creates K3 and writes `c`). B's fetch then brings K3 for a snapshot of the K2 file. Once: B must not use
     * a key fetched for another file, must fetch again, and must end with the item's key and C's data (2 fetches, no write of its
     * own key). Every fetch: the file keeps changing under B's rounds; B must stop after the round bound with `Unavailable`
     * (05: "a file that keeps changing under the rounds can end in Unavailable"), making exactly one fetch per round, moving nothing,
     * and recover when C stops. macOS and Linux.
     */
    @Test
    fun `a key replaced again during the recovery fetch is never adopted and a key that keeps changing ends in Unavailable`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                for (always: Boolean in listOf(false, true)) {
                    val label = "$os, ${if (always) "replaced on every fetch" else "replaced once"}"
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val executorC = NativeExecutor()
                        try {
                            withScriptProvider {
                                runBlocking {
                                    val binding = HookBinding(rig.fake)
                                    val setup: Stale = stale(rig, label) { cache: OsKeyCache -> rig.store(cache = cache, source = hooked(rig, binding)) }
                                    val replacements = AtomicInteger()
                                    binding.onFetch = { number: Int ->
                                        if (always || number == 1) {
                                            // Another process, on its own native thread and key cache, while B is off the lock.
                                            runBlocking {
                                                rig.fake.macItems.clear()
                                                rig.fake.secretItems.clear()
                                                val processC: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache(), executor = executorC)
                                                check(processC.clear() == StorageResult.Success(Unit)) { "$label: C's clear" }
                                                val n: Int = replacements.incrementAndGet()
                                                check(processC.put("c", "$n") == StorageResult.Success(Unit)) { "$label: C's put" }
                                            }
                                        }
                                    }
                                    binding.fetches.set(0)
                                    val answer: StorageResult<String?> = setup.processB.get("c")
                                    binding.onFetch = {}
                                    if (always) {
                                        assertEquals(EncryptedStoreEngine.MAX_ROUNDS, binding.fetches.get(), "$label: one fetch per round, bounded")
                                        val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
                                        assertIs<StorageError.Unavailable>(error, "$label: 05 says Unavailable, was $error")
                                        assertContentEquals(setup.k1, setup.cacheB.get(identity(rig)), "$label: B's cache adopted a key no round proved")
                                    } else {
                                        assertEquals(StorageResult.Success("1"), answer, "$label: B reads C's entry with C's key")
                                        assertEquals(2, binding.fetches.get(), "$label: a key fetched for another file was used, or fetched too often")
                                    }
                                    assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                                    val n: Int = replacements.get()
                                    assertEquals(StorageResult.Success("$n"), setup.processB.get("c"), "$label: B recovers once C stops")
                                    assertContentEquals(itemKey(rig), setup.cacheB.get(identity(rig)), "$label: B's cache does not hold the item's key")
                                }
                            }
                        } finally {
                            executorC.close()
                        }
                    }
                }
            }
        }
        Unit
    }

    /**
     * Two calls of ONE process recover the same stale K1 at once: X's fetch is held while Y runs its round and queues its own fetch
     * behind it (two pending native calls). Then both run. Both must succeed (no double drop that loses the newer entry, no stale
     * key re-inserted over the newer one), the cache must end with the item's key, and every key the binding handed out must be
     * wiped by the end (the cache keeps its own copy). macOS.
     */
    @Test
    fun `two calls of one process recovering together end with one proved key and wipe every fetched copy`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                withScriptProvider {
                    runBlocking {
                        val binding = HookBinding(rig.fake)
                        val setup: Stale = stale(rig, "race") { cache: OsKeyCache -> rig.store(cache = cache, source = hooked(rig, binding)) }
                        binding.handedOut.clear()
                        binding.fetches.set(0)
                        val entered = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        binding.onFetch = { number: Int ->
                            if (number == 1) {
                                entered.countDown()
                                check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                            }
                        }
                        val x: Deferred<StorageResult<String?>> = async(Dispatchers.IO) { setup.processB.get("a") }
                        check(entered.await(HANG_SECONDS, TimeUnit.SECONDS)) { "X's fetch never started" }
                        val y: Deferred<StorageResult<Unit>> = async(Dispatchers.IO) { setup.processB.put("y", "1") }
                        try {
                            while (rig.executor.pendingCalls < 2) yield()
                            assertEquals(2, rig.executor.pendingCalls, "control: X's fetch runs and Y's is queued")
                        } finally {
                            release.countDown()
                        }
                        assertEquals(StorageResult.Success("1"), x.await(), "X")
                        assertEquals(StorageResult.Success(Unit), y.await(), "Y")
                        assertEquals(2, binding.fetches.get(), "control: both calls fetched")
                        assertContentEquals(itemKey(rig), setup.cacheB.get(identity(rig)), "the cache does not hold the item's key")
                        assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("y"), "Y's entry is readable cold")
                        assertEquals(2, binding.handedOut.size, "control: two keys handed out")
                        for ((index: Int, key: ByteArray) in binding.handedOut.withIndex()) {
                            assertTrue(key.all { it == 0.toByte() }, "the key handed out by fetch ${index + 1} was never wiped")
                        }
                    }
                }
            }
        }
        Unit
    }

    /**
     * Seeded storm: process B (stale K1) and process A (healthy, its own cache) run 6 workers each on `Dispatchers.Default`, 6 mixed
     * operations per worker on keys of their own. Every answer must be `Success`, a cold instance must read exactly each worker's last
     * write, B's cache must end with the item's key, B makes at most one fetch per worker (no storm once one call recovered), nothing
     * is quarantined or created. seeds 1..3, macOS and Linux.
     */
    @Test
    fun `a seeded storm of two processes while one recovers loses nothing and fetches at most once per worker`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                for (seed: Int in 1..3) {
                    val label = "seed=$seed, $os"
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        withScriptProvider {
                            runBlocking {
                                val binding = HookBinding(rig.fake)
                                val setup: Stale = stale(rig, label) { cache: OsKeyCache -> rig.store(cache = cache, source = hooked(rig, binding)) }
                                val processA: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                                val writes: Int = keyWrites(rig)
                                binding.fetches.set(0)
                                val start = CompletableDeferred<Unit>()
                                val workers: List<Deferred<Pair<List<String>, Map<String, String?>>>> = (0 until 12).map { worker: Int ->
                                    async(Dispatchers.Default) {
                                        val random = Random(seed * 1000 + worker)
                                        val store: EncryptedKeyValueStorage = if (worker < 6) setup.processB else processA
                                        val problems: MutableList<String> = mutableListOf()
                                        val model: MutableMap<String, String?> = mutableMapOf()
                                        start.await()
                                        repeat(6) { step: Int ->
                                            val key = "w$worker-k${random.nextInt(3)}"
                                            val answer: StorageResult<*> = when (random.nextInt(4)) {
                                                0 -> store.get(key).also { if (it != StorageResult.Success(model[key])) problems += "$label: worker $worker step $step get $key = $it, model ${model[key]}" }
                                                1 -> store.remove(key).also { model[key] = null }
                                                2 -> store.ensureKey()
                                                else -> "v$step".let { value: String -> store.put(key, value).also { model[key] = value } }
                                            }
                                            if (answer !is StorageResult.Success) problems += "$label: worker $worker step $step answered $answer"
                                        }
                                        problems to model
                                    }
                                }
                                start.complete(Unit)
                                val results: List<Pair<List<String>, Map<String, String?>>> = workers.awaitAll()
                                assertEquals(emptyList(), results.flatMap { it.first }, "$label: answers")
                                val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                                for ((_, model: Map<String, String?>) in results) {
                                    for ((key: String, value: String?) in model) assertEquals(StorageResult.Success(value), cold.get(key), "$label: cold read of $key")
                                }
                                assertTrue(binding.fetches.get() in 1..6, "$label: B fetched ${binding.fetches.get()} times for 6 workers")
                                assertEquals(writes, keyWrites(rig), "$label: a key was created, replaced or deleted")
                                assertEquals(emptyList(), quarantined(rig.directory), "$label: something was quarantined")
                                assertContentEquals(itemKey(rig), setup.cacheB.get(identity(rig)), "$label: B's cache does not hold the item's key")
                            }
                        }
                    }
                }
            }
        }
        Unit
    }

    // ---- cancellation in the middle of the recovery ----

    /**
     * B's recovery fetch RUNS (dialogs allowed: no limit) when B's call is cancelled; the item's K2 is returned after B left. The key
     * must be wiped and never cached (B keeps K1, unchanged), the file is untouched, and the next call recovers with one more fetch.
     * Control: the binding handed out a real key.
     */
    @Test
    fun `a recovery cancelled while its fetch runs wipes the fetched key and leaves the stale cache as it was`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                withScriptProvider {
                    runBlocking {
                        val binding = HookBinding(rig.fake)
                        val setup: Stale = stale(rig, "cancel") { cache: OsKeyCache -> rig.store(cache = cache, source = hooked(rig, binding)) }
                        val before: ByteArray = rig.dataFile().readBytes()
                        binding.handedOut.clear()
                        binding.fetches.set(0)
                        val entered = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        binding.afterRead = { number: Int ->
                            if (number == 1) {
                                entered.countDown()
                                check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                            }
                        }
                        val call: Deferred<StorageResult<String?>> = async(Dispatchers.IO) { setup.processB.get("a") }
                        try {
                            check(entered.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the recovery fetch never started" }
                            assertTrue(binding.handedOut.single().any { it != 0.toByte() }, "control: a real key was handed out")
                            call.cancelAndJoin()
                            assertContentEquals(setup.k1, setup.cacheB.get(identity(rig)), "B's cache changed for a caller that left")
                        } finally {
                            release.countDown()
                        }
                        // One native thread: this call runs after the abandoned fetch delivered or discarded its value.
                        assertEquals(StorageResult.Success("1"), setup.processB.get("a"), "the next call recovers")
                        assertEquals(2, binding.fetches.get(), "control: the next call fetched again")
                        assertTrue(binding.handedOut.first().all { it == 0.toByte() }, "the key fetched for the caller that left was never wiped")
                        assertContentEquals(before, rig.dataFile().readBytes(), "the file changed")
                    }
                }
            }
        }
        Unit
    }

    /**
     * B's recovery fetch is QUEUED behind another store's fetch on the shared native thread, and B's own one-thread dispatcher is
     * busy when B's call is cancelled. The fetch is withdrawn at that instant and never runs (B's binding sees no fetch), B's cache
     * keeps K1, and the next call recovers.
     */
    @Test
    fun `a recovery cancelled while its fetch is queued never fetches`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val held: ExecutorService = Executors.newSingleThreadExecutor()
            val heldDispatcher: CoroutineDispatcher = held.asCoroutineDispatcher()
            try {
                SecureTestRig(DesktopOs.MACOS).use { busyRig: SecureTestRig ->
                    SecureTestRig(DesktopOs.MACOS, dispatcher = heldDispatcher).use { rig: SecureTestRig ->
                        withScriptProvider {
                            runBlocking {
                                val binding = HookBinding(rig.fake)
                                val setup: Stale = stale(rig, "queued") { cache: OsKeyCache ->
                                    rig.store(cache = cache, source = hooked(rig, binding), executor = busyRig.executor)
                                }
                                assertEquals(StorageResult.Success(Unit), busyRig.store().put("m", "1"), "control: the other store exists")
                                binding.fetches.set(0)
                                val busy = CountDownLatch(1)
                                val release = CountDownLatch(1)
                                val armed = AtomicBoolean(true)
                                busyRig.fake.before = { name: String ->
                                    if (name == "copyGenericPassword" && armed.compareAndSet(true, false)) {
                                        busy.countDown()
                                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                                    }
                                }
                                val blocker = async(Dispatchers.Default) { busyRig.store(cache = OsKeyCache()).get("m") }
                                val outside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                                val holding = CountDownLatch(1)
                                val unhold = CountDownLatch(1)
                                try {
                                    val call: Deferred<StorageResult<String?>> = try {
                                        check(busy.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the other fetch never started" }
                                        val call: Deferred<StorageResult<String?>> = outside.async { setup.processB.get("a") }
                                        while (busyRig.executor.pendingCalls < 2) yield()
                                        assertEquals(2, busyRig.executor.pendingCalls, "control: B's recovery fetch is queued")
                                        held.execute {
                                            holding.countDown()
                                            unhold.await(HANG_SECONDS, TimeUnit.SECONDS)
                                        }
                                        check(holding.await(HANG_SECONDS, TimeUnit.SECONDS)) { "B's dispatcher was never made busy" }
                                        call.cancel()
                                        assertEquals(1, busyRig.executor.pendingCalls, "the queued recovery fetch was not withdrawn at the cancellation")
                                        call
                                    } finally {
                                        release.countDown()
                                    }
                                    assertEquals(StorageResult.Success("1"), blocker.await(), "control: the other fetch completes")
                                    assertEquals(StorageResult.Success("1"), busyRig.store(cache = OsKeyCache()).get("m"), "control: a later native call is served")
                                    assertEquals(0, binding.fetches.get(), "a withdrawn recovery fetch ran after its caller was cancelled")
                                    unhold.countDown()
                                    assertNotNull(withTimeoutOrNull(HANG_SECONDS.seconds) { call.join() }, "the cancelled call never finished")
                                    assertTrue(call.isCancelled, "control: the call was cancelled")
                                } finally {
                                    unhold.countDown()
                                    outside.cancel()
                                }
                                assertContentEquals(setup.k1, setup.cacheB.get(identity(rig)), "B's cache changed")
                                assertEquals(StorageResult.Success("1"), setup.processB.get("a"), "the next call recovers")
                            }
                        }
                    }
                }
            } finally {
                held.shutdownNow()
            }
        }
        Unit
    }

    // ---- phase A as it reaches a store: a timed-out native call, virtual time ----

    /**
     * A coroutine dispatcher over [target] whose dispatches can be HELD (a busy dispatcher that cannot resume its callers), while its
     * timers (the store's native limit) still run on [target]'s virtual time.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class HoldingDispatcher(private val target: CoroutineDispatcher) : CoroutineDispatcher(), Delay {
        @Volatile
        var held: Boolean = false
        private val heldBlocks: MutableList<Runnable> = mutableListOf()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(heldBlocks) {
                if (held) {
                    heldBlocks += block
                    return
                }
            }
            target.dispatch(context, block)
        }

        fun unhold() {
            val blocks: List<Runnable> = synchronized(heldBlocks) {
                held = false
                heldBlocks.toList().also { heldBlocks.clear() }
            }
            blocks.forEach { target.dispatch(EmptyCoroutineContext, it) }
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) =
            (target as Delay).scheduleResumeAfterDelay(timeMillis, continuation)

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            (target as Delay).invokeOnTimeout(timeMillis, block, context)
    }

    /**
     * A macOS store without dialogs (every native call limited to 10 s) runs on a virtual-time dispatcher. Its first `put` reaches
     * its native install while the shared native thread is busy with another store's fetch, so the install is queued. Then the
     * store's dispatcher is HELD (busy: it cannot resume the caller) and the limit passes on virtual time. 05: the call is withdrawn
     * at that instant, in the thread that fires the limit, not when the dispatcher resumes the caller. So: the queue drops to the
     * running fetch at once; the native thread, freed while the store's dispatcher is still held, serves a later call and NEVER the
     * timed-out store's install (no keychain call of that store is recorded); when the dispatcher runs again the `put` answers a
     * failure, no item and no file are left, and the store works afterwards. Control: the install was queued.
     */
    @Test
    fun `a store call whose native limit passes while queued and its dispatcher is held never makes that call later`() = runTest(timeout = HANG_BOUND) {
        val holding = HoldingDispatcher(StandardTestDispatcher(testScheduler))
        SecureTestRig(DesktopOs.MACOS).use { busyRig: SecureTestRig ->
            SecureTestRig(DesktopOs.MACOS, dispatcher = holding, timeSource = testScheduler.timeSource).use { victimRig: SecureTestRig ->
                val ui = DesktopSecureStorageOptions(allowUserInteraction = true)
                withContext(Dispatchers.Default) {
                    assertEquals(StorageResult.Success(Unit), busyRig.store(options = ui).put("m", "1"), "control: the other store exists")
                }
                val busy = CountDownLatch(1)
                val release = CountDownLatch(1)
                val armed = AtomicBoolean(true)
                busyRig.fake.before = { name: String ->
                    if (name == "copyGenericPassword" && armed.compareAndSet(true, false)) {
                        busy.countDown()
                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the fetch" }
                    }
                }
                val outside = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val blocker: Deferred<StorageResult<String?>> = outside.async { busyRig.store(options = ui, cache = OsKeyCache()).get("m") }
                    val victim: EncryptedKeyValueStorage =
                        victimRig.store(options = DesktopSecureStorageOptions(allowUserInteraction = false), executor = busyRig.executor)
                    val put: Deferred<StorageResult<Unit>> = try {
                        check(busy.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the other fetch never started" }
                        val put: Deferred<StorageResult<Unit>> = async(holding) { victim.put("v", "1") }
                        var spins = 0
                        while (busyRig.executor.pendingCalls < 2 && spins++ < 10_000) {
                            testScheduler.runCurrent()
                            yield()
                        }
                        assertEquals(2, busyRig.executor.pendingCalls, "control: the store's native call is queued behind the busy fetch")
                        assertEquals(emptyList(), victimRig.fake.callNames, "control: the store made no keychain call yet")
                        holding.held = true
                        testScheduler.advanceTimeBy(NativeExecutor.NON_INTERACTIVE_LIMIT + 1.milliseconds)
                        testScheduler.runCurrent()
                        assertFalse(put.isCompleted, "control: the held dispatcher has not resumed the caller")
                        assertEquals(1, busyRig.executor.pendingCalls, "the timed-out call was not withdrawn when its limit passed")
                        put
                    } finally {
                        release.countDown()
                    }
                    withContext(Dispatchers.Default) {
                        assertEquals(StorageResult.Success("1"), blocker.await(), "control: the busy fetch completes")
                        assertEquals(StorageResult.Success("1"), busyRig.store(options = ui, cache = OsKeyCache()).get("m"), "control: a later native call is served")
                    }
                    assertEquals(emptyList(), victimRig.fake.callNames, "a keychain call of the timed-out store ran after its limit passed")
                    holding.unhold()
                    val answer: StorageResult<Unit> = put.await()
                    assertIs<StorageResult.Failure>(answer, "the timed-out put: $answer")
                    assertEquals(0, victimRig.fake.count("addGenericPassword"), "the timed-out install ran")
                    assertEquals(0, victimRig.fake.macItems.size, "the timed-out create left an item")
                    assertFalse(victimRig.dataFile().exists(), "the timed-out create left a file")
                } finally {
                    holding.unhold()
                    outside.cancel()
                }
                // Afterwards, on a real dispatcher over the same directory and OS: the store works.
                SecureTestRig(DesktopOs.MACOS, fake = victimRig.fake).use { after: SecureTestRig ->
                    withContext(Dispatchers.Default) {
                        assertEquals(StorageResult.Success(Unit), after.store(dir = victimRig.directory).put("v", "2"), "the store works afterwards")
                        assertEquals(StorageResult.Success("2"), after.store(dir = victimRig.directory, cache = OsKeyCache()).get("v"), "and reads back cold")
                    }
                }
            }
        }
    }

    // ---- the interrupt rule as it reaches a store ----

    /**
     * A warm OS-key store with an entry runs on a dispatcher of its own (one thread). The provider is interrupted during the key-check
     * (it throws `InterruptedException`, as a PKCS#11 token call does). 05: the failure is typed and "the thread's interrupt status is
     * put back". Through the store: nothing escapes the call (a typed answer), the file is byte-identical, nothing is quarantined or
     * created, the cross-process lock is released (another instance on another dispatcher writes at once), and the dispatch in which
     * the provider was interrupted still carries the interrupt when it ends (no later step of the store inside it swallowed it; the
     * thread pool itself clears the status between two tasks, which is the pool's rule, not the library's). A second call answers
     * typed too and does not hang.
     */
    @Test
    fun `an interrupted provider inside a store call answers typed, releases the lock and leaves the interrupt on the store's thread`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val own: ExecutorService = Executors.newSingleThreadExecutor()
            val endedInterrupted = AtomicInteger()
            val recording: CoroutineDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    own.execute {
                        block.run()
                        if (Thread.currentThread().isInterrupted) endedInterrupted.incrementAndGet()
                    }
                }
            }
            try {
                SecureTestRig(DesktopOs.MACOS, dispatcher = recording).use { rig: SecureTestRig ->
                    withScriptProvider {
                        runBlocking {
                            val store: EncryptedKeyValueStorage = rig.store()
                            assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: a warm store with an entry")
                            assertEquals(0, endedInterrupted.get(), "control: no dispatch ended interrupted before the provider was")
                            val before: ByteArray = rig.dataFile().readBytes()
                            val writes: Int = keyWrites(rig)
                            B9ScriptGcmSpi.arm(interruptAt = setOf(1))
                            val answer: StorageResult<String?> = store.get("k")
                            assertEquals(1, B9ScriptGcmSpi.interrupted.get(), "control: the provider was interrupted")
                            B9ScriptGcmSpi.arm()
                            if (answer != StorageResult.Success("v")) assertFailedWith(answer, StorageOperation.GET, "the interrupted get")
                            // Round 17 (advisor-confirmed contract, lead ruling): a store call never leaves an interrupt status on a thread it found
                            // clear. The boundary still puts the status back for the work that follows on that thread, and InterruptHygiene clears it
                            // when the call's slice on the thread ends, so no dispatch ends interrupted; the interrupt stays visible as the cause.
                            assertEquals(0, endedInterrupted.get(), "05: a dispatch that ran an interrupted provider ended with the interrupt status still set (it reaches the next coroutine on that thread)")
                            val second: StorageResult<String?> = store.get("k")
                            if (second != StorageResult.Success("v")) assertFailedWith(second, StorageOperation.GET, "the next call")
                            assertContentEquals(before, rig.dataFile().readBytes(), "the file changed")
                            assertEquals(writes, keyWrites(rig), "a key was created, replaced or deleted")
                            assertEquals(emptyList(), quarantined(rig.directory), "something was quarantined")
                            withContext(Dispatchers.IO) {
                                SecureTestRig(DesktopOs.MACOS, fake = rig.fake).use { sibling: SecureTestRig ->
                                    val other: EncryptedKeyValueStorage = sibling.store(dir = rig.directory, cache = OsKeyCache())
                                    assertEquals(StorageResult.Success(Unit), other.put("k2", "v2"), "the lock was not released: another instance cannot write")
                                }
                            }
                            assertEquals(StorageResult.Success("v2"), store.get("k2"), "the store works afterwards")
                        }
                    }
                }
            } finally {
                own.shutdownNow()
            }
        }
        Unit
    }

    private companion object {
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 180.seconds
        const val ERR_SEC_INTERACTION_NOT_ALLOWED: Int = -25308
    }
}

/** Serves `AES/GCM/NoPadding` through [B9ScriptGcmSpi]. */
class B9ScriptGcmProvider : Provider(NAME, "1.0", "breaker B9: AES-GCM with scripted PKCS#11-shaped token errors and interrupts") {
    init {
        put("Cipher.AES/GCM/NoPadding", B9ScriptGcmSpi::class.java.name)
    }

    companion object {
        const val NAME: String = "KmpToolkitBreakerB9ScriptGcm"
    }
}

/**
 * Delegates to SunJCE's AES-GCM. Decryptions are numbered from 1 since [arm]; a number in `failAt` (or every one with `failAll`)
 * fails with a token error although the key is right, a number in `interruptAt` throws `InterruptedException`. With
 * [causeOnRealTagFailure] a REAL tag failure is reported the way `P11AEADCipher` reports it (a bad-tag exception with a cause).
 */
class B9ScriptGcmSpi : CipherSpi() {
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
        AEADBadTagException("CKR_DEVICE_ERROR (breaker B9 stand-in)").apply { initCause(ProviderException("token: device error")) }

    private inline fun <T> served(block: () -> T): T {
        if (decrypting) {
            val number: Int = decrypts.incrementAndGet()
            if (number in interruptAt) {
                interrupted.incrementAndGet()
                throw InterruptedException("breaker B9: the token call was interrupted")
            }
            if (failAll || number in failAt) {
                injected.incrementAndGet()
                throw tokenError()
            }
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
        val decrypts: AtomicInteger = AtomicInteger()
        val injected: AtomicInteger = AtomicInteger()
        val interrupted: AtomicInteger = AtomicInteger()

        @Volatile
        var failAt: Set<Int> = emptySet()

        @Volatile
        var failAll: Boolean = false

        @Volatile
        var interruptAt: Set<Int> = emptySet()

        @Volatile
        var causeOnRealTagFailure: Boolean = true

        /** Numbers decryptions from 1 again and sets the script; no arguments = no injected failure. */
        fun arm(failAt: Set<Int> = emptySet(), failAll: Boolean = false, interruptAt: Set<Int> = emptySet()) {
            this.failAt = failAt
            this.failAll = failAll
            this.interruptAt = interruptAt
            decrypts.set(0)
            injected.set(0)
            interrupted.set(0)
        }
    }
}
