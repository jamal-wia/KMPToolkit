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
import java.security.Provider
import java.security.SecureRandom as JcaSecureRandom
import java.security.Security
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Breaker round 5 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-5), against task/sto-desktop cce622f0:
 * the round-7 fix of B4-m2 (a JDK without AES-GCM or PBKDF2) on the paths the round-4 tests did not reach.
 *
 * 05 ("A JDK without AES-GCM or PBKDF2"): every call that needs the missing algorithm "answers `OperationFailed` ... and
 * writes, creates and moves nothing; a store with data is never read as key lost". The rows of plan 4.4 that DECIDE to move
 * or replace the user's file (a lost key with entries: quarantine; a lost key with zero entries: replace in place; an
 * unparsable file: quarantine) reach the cipher only inside `create`, so a missing algorithm must stop them before the
 * quarantine or the replace, and before any key-store write. D-P9 (05, the app-key store): "the arrays this library makes ...
 * are wiped as soon as they are used". `SunJCE` is removed for one call and restored at its position (skipped when another
 * provider still serves the algorithms). Fake binding only.
 */
class EncryptedStoreCryptoProviderBreakerR6B5Test {

    private fun <T> withoutSunJce(block: () -> T): T {
        val sunJce: Provider = Security.getProvider("SunJCE") ?: error("control: this JDK has SunJCE")
        val position: Int = Security.getProviders().indexOf(sunJce) + 1
        Security.removeProvider("SunJCE")
        try {
            val gcmGone: Boolean = runCatching { Cipher.getInstance("AES/GCM/NoPadding") }.isFailure
            val pbkdf2Gone: Boolean = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }.isFailure
            Assume.assumeTrue("another provider still serves AES-GCM or PBKDF2 here", gcmGone && pbkdf2Gone)
            return block()
        } finally {
            Security.insertProviderAt(sunJce, position)
        }
    }

    private fun typed(label: String, call: suspend () -> StorageResult<*>): StorageError {
        val outcome: Result<StorageResult<*>> = withoutSunJce { runBlocking { runCatching { call() } } }
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
        return assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
    }

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    /** The key-store writes of every OS (a lost-key row must not run one when it cannot finish). */
    private val keyStoreWrites: Set<String> = setOf("addGenericPassword", "deleteGenericPassword", "store", "clear")

    private fun assertRefusedAndUntouched(
        label: String,
        rig: SecureTestRig,
        operation: StorageOperation,
        entry: String?,
        call: suspend () -> StorageResult<*>,
    ) {
        val before: ByteArray = rig.dataFile().readBytes()
        val quarantinedBefore: List<String> = rig.quarantined()
        val mac: Map<Pair<String, String>, List<Byte>> = rig.fake.macItems.mapValues { it.value.toList() }
        val secrets: Set<Map<String, String>> = rig.fake.secretItems.keys.toSet()
        val calls: Int = rig.fake.calls.size

        val error: StorageError = typed(label, call)
        val failed = assertIs<StorageError.OperationFailed>(error, "$label: a missing algorithm must be OperationFailed: $error")
        assertEquals(operation, failed.operation, "$label: operation")
        assertEquals(entry, failed.key, "$label: entry key")
        assertContentEquals(before, rig.dataFile().readBytes(), "$label: the user's file was replaced or changed")
        assertEquals(quarantinedBefore, rig.quarantined(), "$label: the user's file was moved to quarantine")
        assertEquals(mac, rig.fake.macItems.mapValues { it.value.toList() }, "$label: the keychain changed")
        assertEquals(secrets, rig.fake.secretItems.keys.toSet(), "$label: the secret service changed")
        val writes: List<String> = rig.fake.callNames.drop(calls).filter { it in keyStoreWrites }
        assertEquals(emptyList(), writes, "$label: a key-store write ran for a create that cannot seal its key-check")
    }

    /**
     * The lost-key and unparsable rows without AES-GCM: macOS (with UI: a definitive not-found) and Linux (without UI: the
     * preflight runs first) with an entry whose item is gone (AfterQuarantine), macOS with a zero-entry file whose item is
     * gone (ReplaceEmpty, for put and ensureKey), Windows with a blob DPAPI cannot open (another store's file: lost), and an
     * unparsable file (AfterQuarantine). Red on: the file quarantined or replaced, a key-store delete/add/store/clear, or an
     * answer that is not `OperationFailed(op, key)`. Control at the end of each row: with the algorithm back the same instance
     * goes ahead (so the row really was a create row).
     */
    @Test
    fun `the lost-key and unparsable rows without AES-GCM never quarantine replace or touch the key store`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val options = DesktopSecureStorageOptions(allowUserInteraction = os == DesktopOs.MACOS)
                    assertEquals(StorageResult.Success(Unit), rig.store(options = options).put("a", "1"), "$os: control")
                    rig.fake.macItems.clear()
                    rig.fake.secretItems.clear()
                    val store: EncryptedKeyValueStorage = rig.store(options = options, cache = OsKeyCache())
                    assertRefusedAndUntouched("$os lost key with an entry, put", rig, StorageOperation.PUT, "k") { store.put("k", "v") }
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the row quarantines once AES-GCM is back")
                    assertEquals(1, rig.quarantined().size, "$os: control, the lost-key row was a quarantine row")
                }
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("a", "1"), "zero entries: control")
                assertEquals(StorageResult.Success(Unit), rig.store().remove("a"), "zero entries: control")
                rig.fake.macItems.clear()
                val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                assertRefusedAndUntouched("macOS lost key, zero entries, put", rig, StorageOperation.PUT, "k") { store.put("k", "v") }
                assertRefusedAndUntouched("macOS lost key, zero entries, ensureKey", rig, StorageOperation.ENSURE_KEY, null) { store.ensureKey() }
                assertEquals(StorageResult.Success(Unit), store.ensureKey(), "zero entries: control, repaired once AES-GCM is back")
            }
            SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store(name = "other").put("a", "1"), "windows: control")
                rig.dataFile(name = "other").copyTo(rig.dataFile())
                val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                assertRefusedAndUntouched("windows lost blob with an entry, put", rig, StorageOperation.PUT, "k") { store.put("k", "v") }
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "windows: control, the row quarantines once AES-GCM is back")
                assertEquals(1, rig.quarantined().size, "windows: control, the lost-key row was a quarantine row")
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                rig.dataFile().writeBytes("not a store file at all".toByteArray())
                val store: EncryptedKeyValueStorage = rig.store()
                assertRefusedAndUntouched("unparsable file, put", rig, StorageOperation.PUT, "k") { store.put("k", "v") }
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "unparsable: control, quarantined once AES-GCM is back")
                assertEquals(1, rig.quarantined().size, "unparsable: control, the row was a quarantine row")
            }
        }
    }

    /**
     * The cipher's random source, deterministic: every array is filled from `Random(SEED)` (the JCA parent's own generator is
     * never used), and every 32-byte array is kept: those are the data keys `EnvelopeCipher.newDataKey` makes.
     */
    private class DataKeyRecorder : JcaSecureRandom() {
        private val source: Random = Random(SEED)
        val dataKeys: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            source.nextBytes(bytes)
            if (bytes.size == SecureFileFormat.DATA_KEY_BYTES) dataKeys += bytes
        }
    }

    /**
     * The primary scenario of B4-m2, a first put without AES-GCM, on the OS-key store (macOS, Windows) and the raw app-key
     * store: the create makes a fresh data key (`newDataKey`), then sealing its key-check (OS-key, `OsKeyStoreEngine.createPlan`)
     * or wrapping it (app-key, `AppKeyStoreEngine.newFile`) throws, and the plan whose `dispose` wipes that key does not exist
     * yet. Every array the library made must be wiped once the call has answered (D-P9). Control: the recorder saw the key
     * (it existed) and the answer is the typed failure of the round-7 fix. Red on: a non-zero data key after the call.
     */
    @Test
    fun `a create that cannot seal its key-check wipes the data key it made`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val leaks: MutableList<String> = mutableListOf()
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val recorder = DataKeyRecorder()
                    val store: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(recorder))
                    assertIs<StorageError.OperationFailed>(typed("$os first put", suspend { store.put("k", "v") }), "$os: control")
                    assertTrue(recorder.dataKeys.isNotEmpty(), "$os: control, a data key was made (seed=$SEED)")
                    if (recorder.dataKeys.any { key: ByteArray -> key.any { it != 0.toByte() } }) leaks += "$os OS-key create"
                }
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val recorder = DataKeyRecorder()
                val raw = JvmAppKeyEncryptedKeyValueStorage(
                    "wipe", rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 7 } }, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(recorder),
                )
                assertIs<StorageError.OperationFailed>(typed("raw first put", suspend { raw.put("k", "v") }), "raw: control")
                assertTrue(recorder.dataKeys.isNotEmpty(), "raw: control, a data key was made (seed=$SEED)")
                if (recorder.dataKeys.any { key: ByteArray -> key.any { it != 0.toByte() } }) leaks += "raw app-key create"
                assertTrue(!File(rig.directory, "${appKeyStoreId("wipe")}.bin").exists(), "raw: no file")
            }
            assertEquals(emptyList(), leaks, "a data key made for a create that failed to seal was left unwiped (seed=$SEED)")
        }
        Unit
    }

    /** Fails the [failAt]-th `sealEntry` (1-based) like a provider that breaks in the middle of a re-key. */
    private class SealFailsAt(private val failAt: Int) : EnvelopeCipher() {
        val seals: AtomicInteger = AtomicInteger()

        override fun sealEntry(dataKey: ByteArray, kind: StoreKind, key: String, value: ByteArray): EncryptedEntry {
            if (seals.incrementAndGet() == failAt) throw IllegalStateException("The AES-GCM provider failed")
            return super.sealEntry(dataKey, kind, key, value)
        }
    }

    /**
     * `changeKey` whose sealing breaks after the first of three entries was re-encrypted (decryption still works: the
     * round-7 tests only removed the algorithm for the whole call, so the re-key never got this far). Contract: the re-key
     * replaces the file atomically or not at all; on failure the instance stays on its old key. Red on: an exception out of
     * `changeKey`, a changed file, an instance bound to the new key, or an old key that no longer opens the store.
     */
    @Test
    fun `a re-key whose sealing breaks midway answers OperationFailed and leaves the store on its old key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val oldKey = AppEncryptionKey.Raw { ByteArray(32) { 3 } }
                val newKey = AppEncryptionKey.Raw { ByteArray(32) { 4 } }
                val cipher = SealFailsAt(failAt = 6) // 3 puts seal 3 entries; the re-key's second seal is the 6th
                val store = JvmAppKeyEncryptedKeyValueStorage("rekey", rig.directory, oldKey, Dispatchers.IO, CrossProcessLock(), cipher = cipher)
                for (entry: String in listOf("a", "b", "c")) assertEquals(StorageResult.Success(Unit), store.put(entry, "v-$entry"), "control")
                val file = File(rig.directory, "${appKeyStoreId("rekey")}.bin")
                val before: ByteArray = file.readBytes()

                val outcome: Result<StorageResult<Unit>> = runCatching { store.changeKey(newKey) }
                assertTrue(outcome.isSuccess, "an exception escaped changeKey: ${outcome.exceptionOrNull()}")
                val failed = assertIs<StorageError.OperationFailed>(assertIs<StorageResult.Failure>(outcome.getOrThrow()).error)
                assertEquals(StorageOperation.CHANGE_KEY, failed.operation)
                assertEquals(null, failed.key)
                assertEquals(6, cipher.seals.get(), "control: the re-key failed at its second seal")
                assertContentEquals(before, file.readBytes(), "the re-key changed the file")
                assertEquals(StorageResult.Success("v-b"), store.get("b"), "the same instance must still be on the old key")
                assertEquals(StorageResult.Success(Unit), store.put("d", "v-d"), "the same instance writes with the old key")
                val fresh = JvmAppKeyEncryptedKeyValueStorage("rekey", rig.directory, oldKey, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success("v-a"), fresh.get("a"), "the old key no longer opens the store")
                val withNew = JvmAppKeyEncryptedKeyValueStorage("rekey", rig.directory, newKey, Dispatchers.IO, CrossProcessLock())
                assertIs<StorageError.AccessDenied>(assertIs<StorageResult.Failure>(withNew.get("a")).error, "the new key opens a store that was never re-keyed")
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
        const val SEED: Int = 505
    }
}
