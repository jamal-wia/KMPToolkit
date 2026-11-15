package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, phase A breaker round 7 (decisions.md D-23), part A7: the AES-GCM primitive of format v1 (plan 4.3,
 * `EnvelopeCipher.gcmDecrypt`) against its own KDoc and D-P12:
 *
 * "A tag that does not verify is the only failure that answers 'wrong key or tampered' (null / false); any other
 * security-provider failure ... is thrown as an IllegalStateException, so an environment problem is never mistaken for lost
 * data (that verdict makes an app delete things)."
 *
 * The JDK's own PKCS#11 provider breaks that premise. `SunPKCS11` serves `AES/GCM/NoPadding` first under a FIPS-configured JDK
 * (RHEL FIPS mode, NSS), and `P11AEADCipher.handleException` (JDK 21 `jdk.crypto.cryptoki/sun/security/pkcs11/P11AEADCipher.java`
 * :827-831) reports a token's `CKR_GENERAL_ERROR` on decryption as `AEADBadTagException`, with the `PKCS11Exception` as its cause
 * ("can't differentiate"). A token error therefore reaches `gcmDecrypt` in exactly the shape of a tag mismatch, and the library
 * answers it `null`: wrong key or tampered. SunJCE's own tag failure never carries a cause (`GaloisCounterMode.java` :1545, :1652
 * `new AEADBadTagException("Tag mismatch")`), so the two are told apart by that cause, structurally, without reading a message.
 *
 * The stand-in: a provider at the first position whose AES-GCM engine delegates to SunJCE, and, while armed, fails a decryption
 * the way `P11AEADCipher` does. Encryption keeps working (the token error is on the decrypt). The store-level test shows what the
 * `null` leads to on an OS-key store: the key-check "fails", the key is read as lost, a put quarantines the user's file and
 * replaces the key in the OS key store, so the quarantined data can never be opened again. The provider is removed in `finally`.
 *
 * The other tests here pin the decisions of the round-8 JCA audit that are not defects (green): a plain `RuntimeException` a
 * provider has no business throwing (a `NullPointerException` from a broken engine) and the `InternalError` the JDK's DRBG throws
 * for a seed source it cannot read (`sun/security/provider/SeedGenerator.java` :548-556) are not typed, but they leave nothing
 * behind: no file, no temporary file, no lock held, and the same instance works afterwards.
 */
class EnvelopeCipherA7BreakerR7Test {

    private fun <T> withTokenProvider(block: () -> T): T {
        val provider = A7TokenGcmProvider()
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            A7TokenGcmSpi.failDecrypt.set(false)
            A7TokenGcmSpi.failEncryptWithNpe.set(false)
            Security.removeProvider(provider.name)
        }
    }

    private fun expectProviderFailure(what: String, outcome: Result<Any?>) {
        val thrown: Throwable = outcome.exceptionOrNull()
            ?: fail(
                "$what: a token error that the JDK reports as AEADBadTagException WITH a cause (P11AEADCipher, CKR_GENERAL_ERROR) " +
                    "was answered as 'wrong key or tampered' (${outcome.getOrNull()}); KDoc: only a tag that does not verify answers that, " +
                    "any other provider failure is an IllegalStateException",
            )
        assertIs<IllegalStateException>(thrown, "$what: ${thrown::class.java.name}")
        assertNotNull(thrown.cause, "$what: the provider failure is the cause")
    }

    /**
     * The primitive and its three users: `gcmDecrypt`, `verifyKeyCheck` (the "key lost" verdict of an OS-key store) and
     * `unwrapDataKey` (the "wrong key" verdict of an app-key store). Control: with the stand-in installed but not armed every one
     * of them works, and a real tag mismatch under the stand-in still answers `null` / `false`.
     */
    @Test
    fun `a token error reported as a tag failure is a provider failure, never wrong key`() {
        val key = ByteArray(32) { 7 }
        val aad = ByteArray(3) { 1 }
        val cipher = EnvelopeCipher()
        val sealed: ByteArray = EnvelopeCipher.gcmEncrypt(key, ByteArray(12), aad, ByteArray(4) { 9 })
        val prefix = ByteArray(8) { 2 }
        val keyCheck: ByteArray = cipher.sealKeyCheck(key, prefix)
        val wrapAad = ByteArray(5) { 3 }
        val wrapped: ByteArray = cipher.wrapDataKey(key, ByteArray(32) { 4 }, wrapAad)
        withTokenProvider {
            // Controls through the stand-in: it really is the engine in use, and it decrypts and detects a real mismatch.
            assertContentEquals(ByteArray(4) { 9 }, EnvelopeCipher.gcmDecrypt(key, ByteArray(12), aad, sealed), "control: the stand-in decrypts")
            assertEquals(null, EnvelopeCipher.gcmDecrypt(ByteArray(32) { 8 }, ByteArray(12), aad, sealed), "control: a real tag mismatch is still null")
            assertTrue(cipher.verifyKeyCheck(key, prefix, keyCheck), "control: the key-check verifies through the stand-in")
            assertTrue(A7TokenGcmSpi.decrypts.get() >= 3, "control: the stand-in served the decryptions (${A7TokenGcmSpi.decrypts.get()})")

            A7TokenGcmSpi.failDecrypt.set(true)
            expectProviderFailure("gcmDecrypt", runCatching { EnvelopeCipher.gcmDecrypt(key, ByteArray(12), aad, sealed) })
            expectProviderFailure("verifyKeyCheck (an OS-key store reads 'false' as key lost)", runCatching { cipher.verifyKeyCheck(key, prefix, keyCheck) })
            expectProviderFailure("unwrapDataKey (an app-key store reads 'null' as a wrong key)", runCatching { cipher.unwrapDataKey(key, wrapped, wrapAad) })
        }
    }

    /**
     * What the `null` above does to a store: an OS-key store with data, read by an instance without a cached key while the token
     * fails one decryption. The contract (D-P12, 05 "A JDK without AES-GCM or PBKDF2"): `OperationFailed`, nothing moved, never
     * "key lost". Red: the put succeeds after quarantining the file and replacing the OS key, so the old value is gone for good.
     */
    @Test
    fun `an OS-key store never quarantines its data for a token error that looks like a tag failure`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val store: EncryptedKeyValueStorage = rig.store()
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store exists")
                val before: ByteArray = rig.dataFile().readBytes()
                val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                val outcome: Result<StorageResult<Unit>> = withTokenProvider {
                    A7TokenGcmSpi.failDecrypt.set(true)
                    runBlocking { runCatching { cold.put("k2", "v2") } }
                }
                val after: List<String> = rig.fileNames()
                val readBack: StorageResult<String?> = rig.store(cache = OsKeyCache()).get("k")
                assertTrue(outcome.isSuccess, "an exception escaped put: ${outcome.exceptionOrNull()}")
                val answer: StorageResult<Unit> = outcome.getOrThrow()
                val failure: StorageResult.Failure = assertIs<StorageResult.Failure>(
                    answer,
                    "a token error was read as 'key lost': put answered $answer, the files are now $after, and the old value reads " +
                        "back as $readBack (the OS key was replaced, so the quarantined file can never be opened again)",
                )
                assertIs<StorageError.OperationFailed>(failure.error, "a provider failure is not a lost key: ${failure.error}")
                assertEquals(emptyList(), after.filter { ".undecryptable-" in it }, "nothing may be quarantined for a provider failure")
                assertContentEquals(before, rig.dataFile().readBytes(), "the store file must be byte-identical")
                assertEquals(StorageResult.Success("v"), readBack, "the old value must still read once the token works")
            }
        }
        Unit
    }

    /**
     * Audit decision (green): a `RuntimeException` outside the JCA contract (a broken engine's `NullPointerException` while it
     * seals) is not typed by the library, but it leaves nothing behind on an existing store and on a first put: the file is
     * byte-identical or absent, no temporary file stays, the store's locks are released (the same instance and a second instance
     * write at once afterwards). Red on: a lock left held (the bounded writes hang), a half file, a temporary file left.
     */
    @Test
    fun `a provider that throws a plain RuntimeException while sealing leaves no state behind`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val store: EncryptedKeyValueStorage = rig.store()
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store exists")
                val before: ByteArray = rig.dataFile().readBytes()
                val namesBefore: List<String> = rig.fileNames()
                val existing: Result<StorageResult<Unit>> = withTokenProvider {
                    A7TokenGcmSpi.failEncryptWithNpe.set(true)
                    runBlocking { runCatching { store.put("k2", "v2") } }
                }
                existing.exceptionOrNull()?.let { assertIs<NullPointerException>(it, "only the provider's own exception may leave: $it") }
                assertContentEquals(before, rig.dataFile().readBytes(), "the failed seal changed the store file")
                assertEquals(namesBefore, rig.fileNames(), "the failed seal left a file behind")
                assertEquals(StorageResult.Success(Unit), store.put("k3", "v3"), "the same instance writes again (no lock left held)")
                assertEquals(StorageResult.Success("v"), rig.store(cache = OsKeyCache()).get("k"), "a second instance reads")

                // A first put of a new store: the create seals its key-check with the broken engine.
                val fresh: EncryptedKeyValueStorage = rig.store(name = "a7-fresh")
                val namesBeforeCreate: List<String> = rig.fileNames()
                val created: Result<StorageResult<Unit>> = withTokenProvider {
                    A7TokenGcmSpi.failEncryptWithNpe.set(true)
                    runBlocking { runCatching { fresh.put("k", "v") } }
                }
                created.exceptionOrNull()?.let { assertIs<NullPointerException>(it, "only the provider's own exception may leave: $it") }
                assertTrue(!rig.dataFile("a7-fresh").exists(), "a create whose seal failed left a store file")
                assertEquals(
                    namesBeforeCreate.filterNot { it.endsWith(".lock") },
                    rig.fileNames().filterNot { it.endsWith(".lock") },
                    "a create whose seal failed left a file behind",
                )
                assertEquals(StorageResult.Success(Unit), fresh.put("k", "v"), "the same instance creates the store afterwards")
            }
        }
        Unit
    }

    /**
     * Audit decision (green, and evidence for m2): the JDK's DRBG reports a seed source it cannot read as `InternalError`
     * (`SeedGenerator.URLSeedGenerator.getSeedBytes`), not as `ProviderException`, and DRBG is the default `SecureRandom` on
     * Windows and wherever `securerandom.source` is not exactly `file:/dev/random` or `file:/dev/urandom` (`SunEntries` :362-365,
     * e.g. the common `-Djava.security.egd=file:/dev/./urandom`). Such an `Error` is not typed (it is a `VirtualMachineError`), but a
     * first put that meets it creates nothing and holds nothing.
     */
    @Test
    fun `a random source that fails with the DRBG's InternalError creates nothing and holds no lock`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val broken: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(A7SeedlessRandom()))
                val outcome: Result<StorageResult<Unit>> = runCatching { broken.put("k", "v") }
                val thrown: Throwable? = outcome.exceptionOrNull()
                assertTrue(thrown == null || thrown is InternalError, "only the random source's own Error may leave: $thrown")
                assertTrue(!rig.dataFile().exists(), "no store file after a failed create")
                assertEquals(emptyList(), rig.fileNames().filterNot { it.endsWith(".lock") }, "nothing but the lock file may stay")
                assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "control: a working random source creates the store at once")
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}

/** Serves `AES/GCM/NoPadding` through [A7TokenGcmSpi]. */
class A7TokenGcmProvider : Provider("KmpToolkitBreakerA7Token", "1.0", "breaker A7: AES-GCM that fails like a PKCS#11 token") {
    init {
        put("Cipher.AES/GCM/NoPadding", A7TokenGcmSpi::class.java.name)
    }
}

/**
 * Delegates to SunJCE's AES-GCM. While [failDecrypt] is set, a decryption fails as `P11AEADCipher` reports a token's
 * `CKR_GENERAL_ERROR`: `AEADBadTagException` with the token error as its cause. While [failEncryptWithNpe] is set, an encryption
 * throws a `NullPointerException` (a broken engine).
 */
class A7TokenGcmSpi : CipherSpi() {
    private var delegate: Cipher? = null
    private var mode: Int = 0

    private fun target(): Cipher = checkNotNull(delegate) { "not initialised" }

    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = target().getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = delegate?.iv
    override fun engineGetParameters(): AlgorithmParameters? = delegate?.parameters

    private fun open(opmode: Int): Cipher {
        mode = opmode
        return Cipher.getInstance("AES/GCM/NoPadding", checkNotNull(Security.getProvider("SunJCE")) { "control: SunJCE exists" })
            .also { delegate = it }
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

    private fun beforeFinal() {
        if (mode == Cipher.DECRYPT_MODE) {
            decrypts.incrementAndGet()
            if (failDecrypt.get()) {
                throw AEADBadTagException("CKR_GENERAL_ERROR (breaker A7 stand-in)").apply { initCause(ProviderException("token general error")) }
            }
        }
        if (mode == Cipher.ENCRYPT_MODE && failEncryptWithNpe.get()) throw NullPointerException("a broken engine (breaker A7 stand-in)")
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray {
        beforeFinal()
        return target().doFinal(input, inputOffset, inputLen)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int {
        beforeFinal()
        return target().doFinal(input, inputOffset, inputLen, output, outputOffset)
    }

    companion object {
        val failDecrypt: AtomicBoolean = AtomicBoolean(false)
        val failEncryptWithNpe: AtomicBoolean = AtomicBoolean(false)
        val decrypts: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
    }
}

/** A `SecureRandom` whose seed source cannot be read, failing the way the JDK's DRBG does (`InternalError`). Nothing is drawn. */
internal fun A7SeedlessRandom(): SecureRandom = SecureRandom.getInstance("A7Seedless", A7SeedlessRandomProvider())

/** Serves the algorithm `A7Seedless`. */
class A7SeedlessRandomProvider : Provider("KmpToolkitBreakerA7Seedless", "1.0", "breaker A7: a DRBG without a seed source") {
    init {
        put("SecureRandom.A7Seedless", A7SeedlessRandomSpi::class.java.name)
    }
}

/** Fails like `SeedGenerator.URLSeedGenerator.getSeedBytes` when the seed device cannot be read. */
class A7SeedlessRandomSpi : java.security.SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray?) = Unit
    override fun engineNextBytes(bytes: ByteArray?): Unit = throw InternalError("URLSeedGenerator file:/dev/random generated exception (breaker A7)")
    override fun engineGenerateSeed(numBytes: Int): ByteArray = throw InternalError("URLSeedGenerator file:/dev/random generated exception (breaker A7)")
}
