package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.KeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactorySpi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 7 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-7), against task/sto-desktop 481e9c13: the JCA
 * boundary of round 9 (`Jca`, D-P12) as it reaches the stores.
 *
 * - The documented cost of the PKCS#11 rule (05: "under such a provider a genuinely wrong key or a tampered file also answers
 *   `OperationFailed` ... on such a JDK the way out of a truly lost key is `clear()`"): is the way out real? The stand-in serves
 *   AES-GCM through SunJCE and, like `P11AEADCipher`, attaches a provider cause to every REAL tag failure.
 * - A tampered store under SunJCE is still reported as before (05 table), and under the stand-in the entry-level way out
 *   (`remove`, `put` over the entry) still works.
 * - A random source that fails at the N-th draw of a first create, for every N: nothing created at any N, every data key wiped.
 * - A failing PBKDF2 provider during `changeKey` to a passphrase: typed, never `AccessDenied`, the instance stays on its key.
 * - A failing SHA-256 provider: an OS-key store with a long file name (the pre-lock hashes of `EncryptedFileGuard.locked`), and the
 *   plain store's factory with a long name (round 9 makes it throw; the factory's KDoc names `IllegalArgumentException` only and
 *   01-overview sells "every failure is a value").
 *
 * Every stand-in provider is inserted first and removed in `finally`. Fake binding only, fixed inputs, no sleeps.
 */
class EncryptedStoreCryptoProviderBreakerR8B7Test {

    private fun <T> withProvider(provider: Provider, block: () -> T): T {
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    private fun assertOperationFailed(outcome: Result<StorageResult<*>>, operation: StorageOperation, key: String?, label: String) {
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
        val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: expected OperationFailed, was $error")
        assertEquals(operation, failed.operation, "$label: operation")
        assertEquals(key, failed.key, "$label: key")
    }

    private fun quarantined(directory: File): List<String> = directory.list().orEmpty().filter { ".undecryptable-" in it }

    /** Replaces the key every item of [rig] holds by another 32-byte key: the item is there, the key-check of the file fails. */
    private fun replaceItemKeys(rig: SecureTestRig) {
        val other = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { index: Int -> (index * 13 + 5).toByte() }
        rig.fake.macItems.replaceAll { _, _ -> other.copyOf() }
        rig.fake.secretItems.replaceAll { _, item: FakeNativeKeyStoreBinding.SecretItem ->
            FakeNativeKeyStoreBinding.SecretItem(item.label, Base64.getEncoder().encode(other))
        }
        assertEquals(1, rig.fake.macItems.size + rig.fake.secretItems.size, "control: one item, now holding another key")
    }

    // ---- the PKCS#11 cost: is clear() the way out? ----

    /**
     * An OS-key store (macOS, Linux) whose item holds ANOTHER key than the one the file was sealed with: plan 4.4's "key lost by a
     * failing key-check". Under SunJCE (control) that is "key lost": `clear()` then `put` works. Under a provider that attaches a
     * cause to its real tag failures (the PKCS#11 shape), 05 says every call answers `OperationFailed` and "the way out of a truly
     * lost key is `clear()`". Red on: after `clear()` (which empties the file and keeps its header and key-check) `put` and
     * `ensureKey` still answer `OperationFailed`, on every retry, so the documented way out does not exist and the store is unusable
     * through the API on such a JDK.
     */
    @Test
    fun `under a provider that attaches a cause to real tag failures clear is a real way out of a lost OS key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val broken: MutableList<String> = mutableListOf()
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                // Control: SunJCE reads the same state as key lost, and clear() then put is the way out.
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                    replaceItemKeys(rig)
                    val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                    assertEquals(StorageResult.Failure(StorageError.Undecryptable(null)), store.get("k"), "$os: control, SunJCE reads key lost")
                    assertEquals(StorageResult.Success(Unit), store.clear(), "$os: control, clear under SunJCE")
                    assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "$os: control, put after clear under SunJCE")
                }
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                    replaceItemKeys(rig)
                    val before: ByteArray = rig.dataFile().readBytes()
                    val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                    withProvider(B7CausedTagGcmProvider()) {
                        runBlocking {
                            val tagFailures: Int = B7CausedTagGcmSpi.causedTagFailures.get()
                            assertOperationFailed(runCatching { store.get("k") }, StorageOperation.GET, "k", "$os: get (the documented cost)")
                            assertTrue(B7CausedTagGcmSpi.causedTagFailures.get() > tagFailures, "$os: control, the stand-in served the key-check")
                            assertContentEquals(before, rig.dataFile().readBytes(), "$os: the documented cost moves nothing")
                            val clear: StorageResult<Unit> = store.clear()
                            val put1: StorageResult<Unit> = store.put("k2", "v2")
                            val put2: StorageResult<Unit> = store.put("k2", "v2")
                            val ensure: StorageResult<Unit> = store.ensureKey()
                            val read: StorageResult<String?> = store.get("k2")
                            if (clear != StorageResult.Success(Unit) || put1 != StorageResult.Success(Unit) || read != StorageResult.Success("v2")) {
                                broken += "$os: clear $clear, put $put1, put again $put2, ensureKey $ensure, get $read, quarantined ${quarantined(rig.directory)}"
                            }
                        }
                    }
                }
            }
            assertEquals(emptyList(), broken, "05: 'on such a JDK the way out of a truly lost key is clear()'; after clear() the store must be usable")
        }
        Unit
    }

    /**
     * A tampered store is still reported as before under SunJCE (05 table): a flipped bit in one entry's ciphertext is
     * `Undecryptable(key)` for that key and the others read; a flipped bit in the key-check is "key lost" on an OS-key store
     * (`Undecryptable(null)` for an entry, nothing moved by `get`). Under the PKCS#11 stand-in the entry tamper answers
     * `OperationFailed` (documented), and the entry-level way out of the table still works: `remove` drops the bad entry and `put`
     * overwrites it, the other entries read.
     */
    @Test
    fun `a tampered entry or key-check is reported as before under SunJCE and the entry way out works under the stand-in`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val store: EncryptedKeyValueStorage = rig.store()
                assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control")
                assertEquals(StorageResult.Success(Unit), store.put("b", "2"), "control")
                val healthy: ByteArray = rig.dataFile().readBytes()
                // The last byte of the file is the tag of the last entry ("b").
                val entryTampered: ByteArray = healthy.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
                rig.dataFile().writeBytes(entryTampered)
                assertEquals(StorageResult.Failure(StorageError.Undecryptable("b")), rig.store(cache = OsKeyCache()).get("b"), "SunJCE: a tampered entry")
                assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("a"), "SunJCE: the other entry reads")
                withProvider(B7CausedTagGcmProvider()) {
                    runBlocking {
                        val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        assertOperationFailed(runCatching { cold.get("b") }, StorageOperation.GET, "b", "stand-in: a tampered entry (the documented cost)")
                        assertEquals(StorageResult.Success("1"), cold.get("a"), "stand-in: the other entry reads")
                        assertEquals(StorageResult.Success(Unit), cold.put("b", "3"), "stand-in: put overwrites the tampered entry")
                        assertEquals(StorageResult.Success("3"), cold.get("b"), "stand-in: the overwritten entry reads")
                    }
                }
                rig.dataFile().writeBytes(entryTampered)
                withProvider(B7CausedTagGcmProvider()) {
                    runBlocking {
                        val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        assertEquals(StorageResult.Success(Unit), cold.remove("b"), "stand-in: remove drops the tampered entry")
                        assertEquals(StorageResult.Success(null), cold.get("b"), "stand-in: the dropped entry is gone")
                    }
                }
                // The key-check: its bytes follow the header; flipping the file's last byte of a single-entry-free file is not it, so
                // rebuild a file whose key-check is flipped through the format.
                rig.dataFile().writeBytes(healthy)
                val parsed: ParsedStoreFile = (StoreClassifier.classify(healthy, StoreKind.entries.toSet()) as FileState.Parsed).file
                val badCheck: ByteArray = parsed.keyCheck.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                rig.dataFile().writeBytes(SecureFileFormat.encode(parsed.header, badCheck, parsed.entries))
                val tampered: ByteArray = rig.dataFile().readBytes()
                assertEquals(StorageResult.Failure(StorageError.Undecryptable(null)), rig.store(cache = OsKeyCache()).get("a"), "SunJCE: a tampered key-check is key lost")
                assertContentEquals(tampered, rig.dataFile().readBytes(), "SunJCE: get moves nothing")
                assertEquals(emptyList(), quarantined(rig.directory), "SunJCE: get quarantines nothing")
            }
        }
        Unit
    }

    // ---- the random source failing at every draw of a create ----

    private class Kind(val label: String, val os: DesktopOs?, val passphrase: Boolean = false)

    /**
     * A first `put` on every store type with a random source that fails from its N-th draw on, for every N up to one past the
     * draws a create needs: the answer is `OperationFailed(PUT, "a")` (or `Success` once N is past every draw), and after a failure
     * there is no store file, no temporary file, no item, no native add, and every data key the cipher drew is zero; the next put
     * with a working source creates the store. Seeded (seed in every message).
     */
    @Test
    fun `a random source that fails at any draw of a first create leaves nothing and wipes every key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val kinds: List<Kind> = listOf(
                Kind("macOS", DesktopOs.MACOS), Kind("Linux", DesktopOs.LINUX), Kind("Windows", DesktopOs.WINDOWS),
                Kind("raw app-key", null), Kind("passphrase app-key", null, passphrase = true),
            )
            for (kind: Kind in kinds) {
                var succeededAt: Int? = null
                var n = 1
                while (succeededAt == null && n <= MAX_DRAWS) {
                    SecureTestRig(kind.os ?: DesktopOs.MACOS).use { rig: SecureTestRig ->
                        val seed: Int = SEED + n
                        val label = "${kind.label}, failing from draw $n (seed=$seed)"
                        val random = B6RecordingRandom(seed).apply { failFrom = n }
                        val name = "b7draw"
                        val appKey: AppEncryptionKey = if (kind.passphrase) AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() }) else AppEncryptionKey.Raw { ByteArray(32) { 6 } }
                        val storeFile: File = if (kind.os == null) File(rig.directory, "${appKeyStoreId(name)}.bin") else rig.dataFile(name = name)
                        val store: EncryptedKeyValueStorage = if (kind.os == null) {
                            JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, appKey, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(random))
                        } else {
                            rig.store(name = name, cipher = EnvelopeCipher(random))
                        }
                        val outcome: Result<StorageResult<Unit>> = runCatching { store.put("a", "1") }
                        if (outcome.getOrNull() == StorageResult.Success(Unit)) {
                            succeededAt = n
                            assertTrue(random.draws.get() < n, "$label: control, the create succeeded only with every draw served")
                        } else {
                            assertOperationFailed(outcome, StorageOperation.PUT, "a", label)
                            assertTrue(random.draws.get() >= n, "$label: control, the failing draw was reached (${random.draws.get()})")
                            assertTrue(!storeFile.exists(), "$label: a store file was left")
                            assertEquals(emptyList(), rig.fileNames().filter { it.endsWith(".tmp") }, "$label: a temporary file was left")
                            assertEquals(0, rig.fake.macItems.size + rig.fake.secretItems.size, "$label: an item was left")
                            assertEquals(0, rig.fake.count("addGenericPassword") + rig.fake.count("store"), "$label: a native add ran")
                            assertEquals(emptyList(), random.liveKeys(), "$label: a data key the cipher drew is still live")
                            val next: EncryptedKeyValueStorage = if (kind.os == null) {
                                JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, appKey, Dispatchers.IO, CrossProcessLock())
                            } else {
                                rig.store(name = name)
                            }
                            assertEquals(StorageResult.Success(Unit), next.put("a", "1"), "$label: the next put with a working source")
                            assertEquals(StorageResult.Success("1"), next.get("a"), "$label: and reads back")
                        }
                    }
                    n++
                }
                assertTrue((succeededAt ?: 0) > 1, "${kind.label}: control, the create needs at least one draw and succeeds within $MAX_DRAWS (seed=$SEED)")
            }
        }
        Unit
    }

    // ---- PBKDF2 failing during changeKey ----

    /**
     * A warm raw-key store with entries re-keyed to a passphrase while the PBKDF2 provider fails (a `ProviderException`, the shape of
     * a PKCS#11 token): `OperationFailed(CHANGE_KEY, null)`, never `AccessDenied` (the new key is not "unusable", the environment is),
     * the file byte-identical, the instance still on its raw key (get and put work); once the provider works the same `changeKey`
     * succeeds and a new instance opens the store with the passphrase.
     */
    @Test
    fun `a failing PBKDF2 provider during changeKey to a passphrase is typed and leaves the instance on its key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val name = "b7rekey"
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 6 } }, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control")
                val before: ByteArray = file.readBytes()
                val passphrase = AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() })
                // The JCA fails a SecretKeyFactory over to the next provider, so a failing PBKDF2 is one that EVERY provider fails:
                // SunJCE is taken out for the call (its AES-GCM is still served, through the stand-in, from the captured object).
                val sunJce: Provider = checkNotNull(Security.getProvider("SunJCE")) { "control: SunJCE exists" }
                val position: Int = Security.getProviders().indexOf(sunJce) + 1
                B7CausedTagGcmSpi.delegate = sunJce
                B7CausedTagGcmSpi.addCause = false
                val standIn = B7FailingPbkdf2Provider().apply { put("Cipher.AES/GCM/NoPadding", B7CausedTagGcmSpi::class.java.name) }
                val asked: Int = B7FailingPbkdf2Spi.failures.get()
                val outcome: Result<StorageResult<Unit>> = try {
                    Security.removeProvider(sunJce.name)
                    check(Security.insertProviderAt(standIn, 1) == 1) { "control: the stand-in provider is first" }
                    runBlocking { runCatching { store.changeKey(passphrase) } }
                } finally {
                    Security.removeProvider(standIn.name)
                    Security.insertProviderAt(sunJce, position)
                    B7CausedTagGcmSpi.addCause = true
                }
                assertTrue(Security.getProvider("SunJCE") === sunJce, "control: SunJCE is back")
                assertTrue(B7FailingPbkdf2Spi.failures.get() > asked, "control: the stand-in PBKDF2 was asked")
                assertOperationFailed(outcome, StorageOperation.CHANGE_KEY, null, "changeKey under a failing PBKDF2")
                assertContentEquals(before, file.readBytes(), "the failed changeKey changed the file")
                assertEquals(StorageResult.Success("v"), store.get("k"), "the instance is still on its raw key")
                assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "the instance still writes")
                assertEquals(StorageResult.Success(Unit), store.changeKey(passphrase), "the same changeKey once PBKDF2 works")
                val reopened = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, passphrase, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success("v2"), reopened.get("k2"), "a new instance opens the store with the passphrase")
            }
        }
        Unit
    }

    // ---- SHA-256 failing ----

    /**
     * An OS-key store with SHA-256 failing for its name, in two shapes. LONG (a 253-byte file name): `EncryptedFileGuard.locked`
     * hashes the lock and temporary names before any lock, so every operation, on an absent store and on an existing one, answers
     * `OperationFailed` of its own operation and creates nothing (not even the lock file). SHORT (an ordinary name, the marker in the
     * service only): only the header fingerprint fails (`identityMatches` of a read, the header of a create); the calls that need no
     * identity still work (05 table: a store with no file reads empty, `clear()` never needs the key), the others answer
     * `OperationFailed`. In both: no native call, the file byte-identical, nothing quarantined; the store works once the provider does.
     */
    @Test
    fun `an OS-key store answers a failing SHA-256 at the lock names and at the header fingerprint without any native call`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (long: Boolean in listOf(true, false)) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val name: String = if (long) "b7digest-" + "x".repeat(224 - "b7digest-".length) else "b7fingerprint"
                    if (long) assertEquals(253, rig.dataFile(name = name).name.toByteArray(StandardCharsets.UTF_8).size, "control: the name is hashed")
                    val store: EncryptedKeyValueStorage = rig.store(name = name)
                    suspend fun call(label: String): StorageResult<*> = when (label) {
                        "put" -> store.put("a", "1")
                        "ensureKey" -> store.ensureKey()
                        "get" -> store.get("a")
                        "remove" -> store.remove("a")
                        else -> store.clear()
                    }
                    val operations: Map<String, StorageOperation> = mapOf(
                        "put" to StorageOperation.PUT, "ensureKey" to StorageOperation.ENSURE_KEY, "get" to StorageOperation.GET,
                        "remove" to StorageOperation.REMOVE, "clear" to StorageOperation.CLEAR,
                    )
                    val keys: Map<String, String?> = mapOf("put" to "a", "ensureKey" to null, "get" to "a", "remove" to "a", "clear" to null)
                    for (existing: Boolean in listOf(false, true)) {
                        if (existing) assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: the store works with a working digest")
                        // The short shape takes its locks (a lock file may appear); the long one fails before it names a lock file.
                        fun names(): List<String> = rig.fileNames().filter { long || !it.endsWith(".lock") }
                        val files: List<String> = names()
                        val before: ByteArray? = if (existing) rig.dataFile(name = name).readBytes() else null
                        val nativeCalls: Int = rig.fake.calls.size
                        // Short name: what needs no identity works (absent: get, remove, clear; existing: clear).
                        val works: Set<String> = when {
                            long -> emptySet()
                            existing -> setOf("clear")
                            else -> setOf("get", "remove", "clear")
                        }
                        A7TokenDigestSpi.marker = name.toByteArray(StandardCharsets.UTF_8)
                        try {
                            withProvider(A7TokenDigestProvider()) {
                                val asked: Int = A7TokenDigestSpi.failures.get()
                                runBlocking {
                                    for (label: String in operations.keys.filter { it != "clear" } + "clear") {
                                        val tag = "${if (long) "long" else "short"} name, ${if (existing) "existing" else "absent"} store, $label"
                                        val outcome: Result<StorageResult<*>> = runCatching { call(label) }
                                        if (label in works) {
                                            assertTrue(outcome.getOrNull() is StorageResult.Success<*>, "$tag: needs no identity and must work, was ${outcome.getOrNull() ?: outcome.exceptionOrNull()}")
                                        } else {
                                            assertOperationFailed(outcome, operations.getValue(label), keys[label], tag)
                                        }
                                    }
                                }
                                assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the stand-in digest was asked for the name")
                            }
                        } finally {
                            A7TokenDigestSpi.marker = null
                        }
                        assertEquals(nativeCalls, rig.fake.calls.size, "long=$long existing=$existing: a native call was made: ${rig.fake.calls}")
                        if (existing) {
                            // clear() of the short shape empties the file by design; the long shape changes nothing.
                            if (long) assertContentEquals(before, rig.dataFile(name = name).readBytes(), "the store file changed")
                            assertEquals(files, names(), "long=$long existing=$existing: a file was created or removed")
                        } else {
                            assertEquals(files, names(), "long=$long existing=$existing: a file was created or removed")
                        }
                        assertEquals(emptyList(), quarantined(rig.directory), "long=$long existing=$existing: something was quarantined")
                    }
                    assertEquals(StorageResult.Success(Unit), store.put("b", "2"), "control: the same instance writes once the provider works")
                    assertEquals(StorageResult.Success("2"), store.get("b"), "control: and reads")
                }
            }
        }
        Unit
    }

    /**
     * The plain store's factory with a name whose file name is 240 bytes (hashed) while SHA-256 fails for it. The factory's KDoc
     * names one exception, `IllegalArgumentException` for a missing name, and 01-overview says "Every failure is a value ... only the
     * constructors reject a developer error ... with IllegalArgumentException". A failing provider is no developer error: the
     * factory must not throw, and the store's calls answer a typed failure (as both encrypted stores do for the same names). Red on:
     * the factory throwing `IllegalStateException` (the round-9 decision).
     */
    @Test
    fun `the plain store factory never throws for a failing SHA-256 provider and its calls fail typed`() {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val name: String = "b7plain-" + "x".repeat(210 - "b7plain-".length)
            val fileName = "$name.kmptoolkit.storage.properties"
            assertEquals(240, fileName.toByteArray(StandardCharsets.UTF_8).size, "control: the file name is long enough to be hashed")
            A7TokenDigestSpi.marker = name.toByteArray(StandardCharsets.UTF_8)
            try {
                withProvider(A7TokenDigestProvider()) {
                    val asked: Int = A7TokenDigestSpi.failures.get()
                    val made: Result<KeyValueStorage> = runCatching { createKeyValueStorage(rig.directory, StorageConfig(name)) }
                    assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the stand-in digest was asked for the long name")
                    assertTrue(
                        made.isSuccess,
                        "the factory threw ${made.exceptionOrNull()?.let { it::class.java.name }} for a failing provider; its KDoc names only " +
                            "IllegalArgumentException for a missing name, and every failure of the module is a value",
                    )
                    val put: Result<StorageResult<Unit>> = runBlocking { runCatching { made.getOrThrow().put("a", "1") } }
                    assertTrue(put.isSuccess && put.getOrThrow() is StorageResult.Failure, "put must fail typed under the failing digest, was ${put.getOrNull() ?: put.exceptionOrNull()}")
                }
            } finally {
                A7TokenDigestSpi.marker = null
            }
            val store: KeyValueStorage = createKeyValueStorage(rig.directory, StorageConfig(name))
            assertEquals(StorageResult.Success(Unit), runBlocking { store.put("a", "1") }, "control: the same name works with a working digest")
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
        const val SEED: Int = 707
        const val MAX_DRAWS: Int = 12
    }
}

/** Serves `AES/GCM/NoPadding` through [B7CausedTagGcmSpi]. */
class B7CausedTagGcmProvider : Provider("KmpToolkitBreakerB7CausedTag", "1.0", "breaker B7: AES-GCM whose real tag failures carry a cause") {
    init {
        put("Cipher.AES/GCM/NoPadding", B7CausedTagGcmSpi::class.java.name)
    }
}

/**
 * Delegates to SunJCE's AES-GCM. A REAL tag failure of the delegate is reported the way `P11AEADCipher.handleException` reports
 * every decryption failure of a token ("can't differentiate"): an `AEADBadTagException` whose cause is the provider's exception.
 */
class B7CausedTagGcmSpi : CipherSpi() {
    private var cipher: Cipher? = null

    private fun target(): Cipher = checkNotNull(cipher) { "not initialised" }

    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = target().getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = cipher?.iv
    override fun engineGetParameters(): AlgorithmParameters? = cipher?.parameters

    private fun open(): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding", Companion.delegate ?: checkNotNull(Security.getProvider("SunJCE")) { "control: SunJCE exists" })
            .also { cipher = it }

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        open().init(opmode, key, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        open().init(opmode, key, params, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
        open().init(opmode, key, params, random)
    }

    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {
        target().updateAAD(src, offset, len)
    }

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = target().update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        target().update(input, inputOffset, inputLen, output, outputOffset)

    private inline fun <T> caused(block: () -> T): T =
        try {
            block()
        } catch (mismatch: AEADBadTagException) {
            if (!addCause) throw mismatch
            causedTagFailures.incrementAndGet()
            throw AEADBadTagException("CKR_ENCRYPTED_DATA_INVALID (breaker B7 stand-in)").apply { initCause(ProviderException("token: data invalid")) }
        }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = caused { target().doFinal(input, inputOffset, inputLen) }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        caused { target().doFinal(input, inputOffset, inputLen, output, outputOffset) }

    companion object {
        val causedTagFailures: AtomicInteger = AtomicInteger()

        /** The provider object to delegate to (SunJCE, also while it is taken out of the provider list); `null` = look it up. */
        @Volatile
        var delegate: Provider? = null

        /** `false` passes real tag failures through unchanged (a plain AES-GCM for the PBKDF2 test). */
        @Volatile
        var addCause: Boolean = true
    }
}

/** Serves `SecretKeyFactory.PBKDF2WithHmacSHA256` through [B7FailingPbkdf2Spi]. */
class B7FailingPbkdf2Provider : Provider("KmpToolkitBreakerB7Pbkdf2", "1.0", "breaker B7: PBKDF2 that fails like a PKCS#11 token") {
    init {
        put("SecretKeyFactory.PBKDF2WithHmacSHA256", B7FailingPbkdf2Spi::class.java.name)
    }
}

/** Every derivation fails with a `ProviderException`. */
class B7FailingPbkdf2Spi : SecretKeyFactorySpi() {
    override fun engineGenerateSecret(keySpec: KeySpec?): SecretKey {
        failures.incrementAndGet()
        throw ProviderException("token: PBKDF2 failed (breaker B7 stand-in)")
    }

    override fun engineGetKeySpec(key: SecretKey?, keySpecClass: Class<*>?): KeySpec = throw ProviderException("not supported (breaker B7 stand-in)")

    override fun engineTranslateKey(key: SecretKey?): SecretKey = throw ProviderException("not supported (breaker B7 stand-in)")

    companion object {
        val failures: AtomicInteger = AtomicInteger()
    }
}
