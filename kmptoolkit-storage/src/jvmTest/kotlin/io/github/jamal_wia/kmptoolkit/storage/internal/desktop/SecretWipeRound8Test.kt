package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.security.ProviderException
import java.security.SecureRandom as JcaSecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
 * Group sto-desktop, round 8 (breaker finding B5-m2 and the audit it asked for): every 32-byte key the library makes is
 * wiped when the operation that made it ends, on EVERY way out, not only on the way the plan's `dispose` covers (D-P9).
 *
 * The audit found the paths below (the table is in the round report); each test holds the key (the cipher's random source
 * records every 32-byte array it fills, so the test owns the very array the library used) and has a control assertion that the
 * key existed and was not zero while the operation was still using it:
 *
 * - a create whose key-check sealing or key wrapping fails after the key was drawn (macOS, Linux, raw and passphrase store);
 * - a `changeKey` whose new file cannot be built after its new data key was drawn;
 * - an unwrapped data key whose key-check then fails with a provider failure (app-key store, `get`);
 * - a Windows create whose caller is cancelled while DPAPI protects the key.
 *
 * The failures are injected through the random source (deterministic: `Random(SEED)`, no provider swap) or the cipher's open
 * steps. Fake binding only.
 */
class SecretWipeRound8Test {

    /**
     * The cipher's random source, deterministic: arrays are filled from `Random(SEED)` and the parent generator is never used.
     * Every 32-byte array is kept (the data keys `EnvelopeCipher.newDataKey` makes). From the [failFrom]-th draw on, a draw throws
     * a `ProviderException` (an entropy source that cannot be read).
     */
    private class RecordingRandom : JcaSecureRandom() {
        private val source: Random = Random(SEED)
        val dataKeys: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        @Volatile
        var draws: Int = 0

        @Volatile
        var failFrom: Int = Int.MAX_VALUE

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            draws += 1
            if (draws >= failFrom) throw ProviderException("the entropy source cannot be read (round 8 test)")
            source.nextBytes(bytes)
            if (bytes.size == SecureFileFormat.DATA_KEY_BYTES) dataKeys += bytes
        }
    }

    private fun assertAllWiped(label: String, random: RecordingRandom) {
        assertTrue(random.dataKeys.isNotEmpty(), "$label: control, a data key was made (seed=$SEED)")
        val live: List<Int> = random.dataKeys.withIndex().filter { (_, key: ByteArray) -> key.any { it != 0.toByte() } }.map { it.index }
        assertEquals(emptyList(), live, "$label: data keys left unwiped after the operation ended (seed=$SEED)")
    }

    private fun failed(label: String, outcome: Result<StorageResult<*>>, operation: StorageOperation): StorageError.OperationFailed {
        assertTrue(outcome.isSuccess, "$label: an exception escaped: ${outcome.exceptionOrNull()}")
        val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
        val failure: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: $error")
        assertEquals(operation, failure.operation, label)
        return failure
    }

    /**
     * A first put whose SECOND random draw fails: the data key (draw 1) exists, then the nonce of its key-check (OS-key stores)
     * or of its wrapping (app-key stores) cannot be drawn. The plan whose `dispose` wipes the key does not exist yet.
     */
    @Test
    fun `a create that fails after its data key was drawn leaves no live key behind`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val random = RecordingRandom().also { it.failFrom = 2 }
                    val store: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(random))
                    failed("$os OS-key put", runCatching { store.put("k", "v") }, StorageOperation.PUT)
                    assertAllWiped("$os OS-key create", random)
                    assertTrue(!rig.dataFile().exists(), "$os: no file after a failed create")
                }
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                for ((kind: String, key: AppEncryptionKey) in appKeys()) {
                    val random = RecordingRandom()
                    // A passphrase store draws its salt first (draw 1): the data key is draw 2 and the failing wrap nonce draw 3.
                    random.failFrom = if (kind == "passphrase") 3 else 2
                    val name = "r8-create-$kind"
                    val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(random))
                    failed("$kind put", runCatching { store.put("k", "v") }, StorageOperation.PUT)
                    assertAllWiped("$kind create", random)
                    assertTrue(!File(rig.directory, "${appKeyStoreId(name)}.bin").exists(), "$kind: no file after a failed create")
                }
            }
        }
        Unit
    }

    /** `changeKey` builds the new file (a fresh data key, then its wrapping) before it re-encrypts anything. */
    @Test
    fun `a changeKey whose new file cannot be built wipes the new data key and changes nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val random = RecordingRandom()
                val oldKey = AppEncryptionKey.Raw { ByteArray(32) { 3 } }
                val newKey = AppEncryptionKey.Raw { ByteArray(32) { 4 } }
                val name = "r8-rekey"
                val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, oldKey, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(random))
                assertEquals(StorageResult.Success(Unit), store.put("a", "v-a"), "control: the store exists")
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                val before: ByteArray = file.readBytes()
                val keysBefore: Int = random.dataKeys.size
                random.failFrom = random.draws + 2 // the new data key is the next draw, the nonce of its wrapping the one that fails

                failed("changeKey", runCatching { store.changeKey(newKey) }, StorageOperation.CHANGE_KEY)

                assertTrue(random.dataKeys.size > keysBefore, "control: changeKey drew its new data key (seed=$SEED)")
                assertAllWiped("changeKey", random)
                assertContentEquals(before, file.readBytes(), "the failed re-key changed the file")
                random.failFrom = Int.MAX_VALUE
                assertEquals(StorageResult.Success("v-a"), store.get("a"), "the instance is still on its old key")
            }
        }
        Unit
    }

    /** An app-key cipher whose key-check fails like a provider that breaks between the unwrap and the check. */
    private class CheckFails : EnvelopeCipher() {
        /** A copy of every key the unwrap returned, taken before the check ran: the control that it was live. */
        val unwrapped: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()
        val live: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()

        @Volatile
        var failCheck: Boolean = false

        override fun unwrapDataKey(kek: ByteArray, wrapped: ByteArray, aad: ByteArray): ByteArray? {
            val key: ByteArray? = super.unwrapDataKey(kek, wrapped, aad)
            if (key != null) {
                live += key
                unwrapped += key.copyOf()
            }
            return key
        }

        override fun verifyKeyCheck(dataKey: ByteArray, headerPrefix: ByteArray, keyCheck: ByteArray): Boolean {
            if (failCheck) throw IllegalStateException("The AES-GCM provider failed")
            return super.verifyKeyCheck(dataKey, headerPrefix, keyCheck)
        }
    }

    /**
     * A fresh instance of an existing app-key store derives its KEK, unwraps the data key and then verifies the key-check; a
     * provider failure in that check must answer `OperationFailed` AND wipe the key it just unwrapped (before this round it was
     * dropped live). Red on: a live array in the cipher's record.
     */
    @Test
    fun `an unwrapped data key whose key-check throws is wiped`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                for ((kind: String, key: AppEncryptionKey) in appKeys()) {
                    val name = "r8-unwrap-$kind"
                    val writer = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock())
                    assertEquals(StorageResult.Success(Unit), writer.put("k", "v"), "$kind: control, the store exists")
                    val cipher = CheckFails().also { it.failCheck = true }
                    val reader = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock(), cipher = cipher)

                    failed("$kind get", runCatching { reader.get("k") }, StorageOperation.GET)

                    assertTrue(cipher.unwrapped.isNotEmpty(), "$kind: control, the key was unwrapped")
                    assertTrue(cipher.unwrapped.all { copy: ByteArray -> copy.any { it != 0.toByte() } }, "$kind: control, the unwrapped key was live")
                    val leaked: List<Int> = cipher.live.withIndex().filter { (_, k: ByteArray) -> k.any { it != 0.toByte() } }.map { it.index }
                    assertEquals(emptyList(), leaked, "$kind: the unwrapped data key was left live after the key-check failed")
                    cipher.failCheck = false
                    assertEquals(StorageResult.Success("v"), reader.get("k"), "$kind: control, the same instance reads once the provider works")
                }
            }
        }
        Unit
    }

    /**
     * Windows: the data key is made, a copy is handed to the DPAPI call, and the caller is cancelled while DPAPI runs. The call
     * throws `CancellationException` out of the executor, so the key never reaches a plan; it must be wiped at once (the copy
     * the native thread holds is wiped by that thread when the call returns).
     */
    @Test
    fun `a Windows create whose caller is cancelled while DPAPI protects the key wipes the key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
                val random = RecordingRandom()
                val inside = CountDownLatch(1)
                val release = CountDownLatch(1)
                rig.fake.before = { name: String ->
                    if (name == "protect") {
                        inside.countDown()
                        check(release.await(HANG_SECONDS, TimeUnit.SECONDS)) { "the test never released the DPAPI call" }
                    }
                }
                val store: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(random))
                val put = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { store.put("k", "v") }
                try {
                    check(inside.await(HANG_SECONDS, TimeUnit.SECONDS)) { "DPAPI was never reached" }
                    assertEquals(1, random.dataKeys.size, "control: the data key exists while DPAPI runs (seed=$SEED)")
                    assertTrue(random.dataKeys.single().any { it != 0.toByte() }, "control: the key is live while DPAPI runs")
                    put.cancel()
                    put.join()
                    assertAllWiped("Windows cancelled create", random)
                } finally {
                    release.countDown()
                }
                assertTrue(!rig.dataFile().exists(), "no file after a cancelled create")
            }
        }
        Unit
    }

    private fun appKeys(): Map<String, AppEncryptionKey> = mapOf(
        "raw" to AppEncryptionKey.Raw { ByteArray(32) { 9 } },
        "passphrase" to AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() }),
    )

    private companion object {
        const val SEED: Int = 808
        const val HANG_SECONDS: Long = 60
        val HANG_BOUND = 120.seconds
    }
}
