package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
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
import java.security.SecureRandomSpi
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.CipherSpi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 8 (breaker finding A6-m1), beyond `EnvelopeCipherA6BreakerR6Test`: a security provider that fails
 * in a way that is not a `GeneralSecurityException`, seen from the stores.
 *
 * The contract (05, D-P12, the `EnvelopeCipher` KDoc): an environment problem of the JDK's crypto is never "wrong key" and
 * never "key lost" (those verdicts make an app delete things); every store call answers `OperationFailed` and moves, creates
 * and replaces nothing. The shapes covered here are the three a JCA engine has: `ProviderException` out of the operation
 * (a PKCS#11 token error under a FIPS-configured JDK), `UnsupportedOperationException` out of `updateAAD` (an AES-GCM engine
 * without AAD support) and `ProviderException` out of a `SecureRandom` (an entropy source that cannot be read: every nonce,
 * salt and data key comes from it). The failing provider is inserted for one call and removed in `finally`; every test has a
 * control assertion that the same call worked before, or works after. Fake binding only.
 */
class EncryptedStoreProviderFailureRound8Test {

    private fun <T> withProvider(provider: Provider, block: () -> T): T {
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the failing provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    private fun failingProviders(): List<Provider> = listOf(A6FailingProvider(), R8NoAadProvider())

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    /** The primitives answer the way their KDoc says for the AAD-less engine (the breaker's own test pins `ProviderException`). */
    @Test
    fun `an AES-GCM engine without AAD support is thrown as IllegalStateException on both directions`() {
        val key = ByteArray(32) { 5 }
        // Cipher.updateAAD returns before it reaches the engine for an empty array, so the AAD is not empty (the library's always is).
        val aad = ByteArray(3) { 1 }
        val sealed: ByteArray = EnvelopeCipher.gcmEncrypt(key, ByteArray(12), aad, ByteArray(4))
        withProvider(R8NoAadProvider()) {
            val encrypt: Result<ByteArray> = runCatching { EnvelopeCipher.gcmEncrypt(key, ByteArray(12), aad, ByteArray(4)) }
            val decrypt: Result<ByteArray?> = runCatching { EnvelopeCipher.gcmDecrypt(key, ByteArray(12), aad, sealed) }
            assertIs<IllegalStateException>(encrypt.exceptionOrNull(), "gcmEncrypt: ${encrypt.exceptionOrNull() ?: encrypt.getOrNull()}")
            assertIs<UnsupportedOperationException>(encrypt.exceptionOrNull()?.cause)
            assertIs<IllegalStateException>(decrypt.exceptionOrNull(), "gcmDecrypt must never answer wrong key: ${decrypt.exceptionOrNull() ?: decrypt.getOrNull()}")
            assertIs<UnsupportedOperationException>(decrypt.exceptionOrNull()?.cause)
        }
        assertEquals(20, EnvelopeCipher.gcmEncrypt(key, ByteArray(12), aad, ByteArray(4)).size, "control: it works again")
    }

    /** Every random draw of the cipher (data key, salt, nonce) is typed when the random source fails. */
    @Test
    fun `a random source that fails is thrown as IllegalStateException by every draw`() {
        val broken = EnvelopeCipher(R8BrokenRandom())
        val key = ByteArray(32) { 1 }
        val draws: Map<String, () -> Any?> = mapOf(
            "newDataKey" to { broken.newDataKey() },
            "newSalt" to { broken.newSalt() },
            "sealEntry (nonce)" to { broken.sealEntry(key, StoreKind.OS_ITEM, "k", ByteArray(1)) },
            "sealKeyCheck (nonce)" to { broken.sealKeyCheck(key, ByteArray(4)) },
            "wrapDataKey (nonce)" to { broken.wrapDataKey(key, ByteArray(32), ByteArray(0)) },
        )
        for ((name: String, draw: () -> Any?) in draws) {
            val outcome: Result<Any?> = runCatching(draw)
            val thrown: Throwable = outcome.exceptionOrNull() ?: fail("$name answered although the random source failed")
            assertIs<IllegalStateException>(thrown, "$name: an untyped ${thrown::class.java.name} would escape the store call")
            assertIs<ProviderException>(thrown.cause, "$name: the random source failure is the cause")
        }
        // Control: the same cipher over a working source draws.
        assertEquals(32, EnvelopeCipher().newDataKey().size)
    }

    /**
     * An existing OS-key store, then a provider that fails inside the operation: `get` and `put` answer `OperationFailed`
     * (never `AccessDenied`, never "key lost" with a quarantine), the file is byte-identical and nothing is quarantined; once the
     * provider is gone the same instance reads its value. Red on: an exception out of the call, a quarantine, a changed file.
     */
    @Test
    fun `an existing OS-key store answers a failing AES-GCM provider with OperationFailed and keeps its file`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (provider: Provider in failingProviders()) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val store: EncryptedKeyValueStorage = rig.store()
                        val label = "${provider.name} on $os"
                        assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$label: control, the store exists")
                        val before: ByteArray = rig.dataFile().readBytes()
                        // A second instance has no cached key: it must resolve the key from the key store and open an entry with the provider.
                        val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        val answers: List<Pair<StorageOperation, Result<StorageResult<*>>>> = withProvider(provider) {
                            runBlocking {
                                listOf(
                                    StorageOperation.GET to runCatching { cold.get("k") },
                                    StorageOperation.PUT to runCatching { cold.put("k2", "v2") },
                                    StorageOperation.GET to runCatching { store.get("k") },
                                )
                            }
                        }
                        for ((operation: StorageOperation, outcome: Result<StorageResult<*>>) in answers) {
                            assertTrue(outcome.isSuccess, "$label $operation: an exception escaped the store call: ${outcome.exceptionOrNull()}")
                            val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label $operation: ${outcome.getOrThrow()}").error
                            val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label $operation: a provider failure is neither a wrong key nor a lost key: $error")
                            assertEquals(operation, failed.operation, label)
                            assertNotNull(failed.cause, "$label $operation: the provider failure is the cause")
                        }
                        assertContentEquals(before, rig.dataFile().readBytes(), "$label: the failed calls changed the store file")
                        assertEquals(emptyList(), rig.quarantined(), "$label: a provider failure must never quarantine the file")
                        assertEquals(StorageResult.Success("v"), cold.get("k"), "$label: control, the store reads once the provider is gone")
                    }
                }
            }
        }
        Unit
    }

    /** The same for the app-key stores (raw key and passphrase): the failure is `OperationFailed`, never `AccessDenied`. */
    @Test
    fun `an existing app-key store answers a failing AES-GCM provider with OperationFailed and keeps its file`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val keys: Map<String, AppEncryptionKey> = mapOf(
                "raw" to AppEncryptionKey.Raw { ByteArray(32) { 9 } },
                "passphrase" to AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() }),
            )
            for ((kind: String, key: AppEncryptionKey) in keys) {
                for (provider: Provider in failingProviders()) {
                    SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                        val label = "$kind store with ${provider.name}"
                        val name = "r8-$kind"
                        val writer = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock())
                        assertEquals(StorageResult.Success(Unit), writer.put("k", "v"), "$label: control, the store exists")
                        val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                        val before: ByteArray = file.readBytes()
                        val reader = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock())
                        val answers: List<Result<StorageResult<*>>> = withProvider(provider) {
                            runBlocking { listOf(runCatching { reader.get("k") }, runCatching { reader.put("k2", "v2") }) }
                        }
                        for (outcome: Result<StorageResult<*>> in answers) {
                            assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
                            val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
                            assertIs<StorageError.OperationFailed>(error, "$label: a provider failure is not a wrong key: $error")
                        }
                        assertContentEquals(before, file.readBytes(), "$label: the failed calls changed the store file")
                        assertEquals(emptyList(), rig.quarantined(), "$label: nothing is quarantined")
                        assertEquals(StorageResult.Success("v"), reader.get("k"), "$label: control, it reads once the provider is gone")
                    }
                }
            }
        }
        Unit
    }

    /**
     * A first put whose random source fails (a data key, a salt or a nonce cannot be drawn) answers `OperationFailed` on every
     * store type and creates nothing: no file, no key-store item.
     */
    @Test
    fun `a first put with a failing random source answers OperationFailed and creates nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.WINDOWS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val store: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(R8BrokenRandom()))
                    val outcome: Result<StorageResult<*>> = runCatching { store.put("k", "v") }
                    assertTrue(outcome.isSuccess, "$os: an exception escaped put: ${outcome.exceptionOrNull()}")
                    assertIs<StorageError.OperationFailed>(assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$os").error, "$os")
                    assertTrue(!rig.dataFile().exists(), "$os: no store file after a failed create")
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, a working cipher creates the store")
                }
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                for ((kind: String, key: AppEncryptionKey) in mapOf(
                    "raw" to AppEncryptionKey.Raw { ByteArray(32) { 2 } },
                    "passphrase" to AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() }),
                )) {
                    val name = "r8-random-$kind"
                    val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(R8BrokenRandom()))
                    val outcome: Result<StorageResult<*>> = runCatching { store.put("k", "v") }
                    assertTrue(outcome.isSuccess, "$kind: an exception escaped put: ${outcome.exceptionOrNull()}")
                    assertIs<StorageError.OperationFailed>(assertIs<StorageResult.Failure>(outcome.getOrThrow(), kind).error, kind)
                    assertTrue(!File(rig.directory, "${appKeyStoreId(name)}.bin").exists(), "$kind: no store file after a failed create")
                }
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}

/**
 * A `SecureRandom` whose engine fails the way an unreadable entropy source does (`ProviderException`). It is served by an own
 * provider through `SecureRandom.getInstance(algorithm, provider)`, so nothing is installed globally and nothing is drawn.
 */
internal fun R8BrokenRandom(): SecureRandom = SecureRandom.getInstance("R8Broken", R8BrokenRandomProvider())

/** Serves the algorithm `R8Broken`. */
class R8BrokenRandomProvider : Provider("KmpToolkitRound8BrokenRandom", "1.0", "round 8: a random source that fails") {
    init {
        put("SecureRandom.R8Broken", R8BrokenRandomSpi::class.java.name)
    }
}

/** An engine that cannot read its entropy. */
class R8BrokenRandomSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray?) = Unit
    override fun engineNextBytes(bytes: ByteArray?): Unit = throw ProviderException("the entropy source cannot be read (round 8 test)")
    override fun engineGenerateSeed(numBytes: Int): ByteArray = throw ProviderException("the entropy source cannot be read (round 8 test)")
}

/** A provider that serves AES-GCM through an engine without AAD support (`updateAAD` is not supported). */
class R8NoAadProvider : Provider("KmpToolkitRound8NoAad", "1.0", "round 8: AES-GCM without AAD support") {
    init {
        put("Cipher.AES/GCM/NoPadding", R8NoAadGcmSpi::class.java.name)
    }
}

/** Initialises like any engine and refuses `updateAAD` with `UnsupportedOperationException`, the base class's default. */
class R8NoAadGcmSpi : CipherSpi() {
    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = inputLen + 16
    override fun engineGetIV(): ByteArray? = null
    override fun engineGetParameters(): AlgorithmParameters? = null
    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) = Unit
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = ByteArray(0)
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = 0
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = ByteArray(0)
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int = 0
}
