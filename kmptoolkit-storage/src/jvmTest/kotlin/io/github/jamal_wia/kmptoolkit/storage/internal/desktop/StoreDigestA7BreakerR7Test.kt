package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.ProviderException
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
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
 * Group sto-desktop, phase A breaker round 7 (decisions.md D-23), part A7: the JCA calls of the storage module that the round-8
 * audit left untyped, `MessageDigest.getInstance("SHA-256")`, against the store contract "nothing throws" and D-P12 ("a crypto
 * provider that cannot do what a store needs is an environment problem, in every shape a JCA engine reports it ... a store answers
 * OperationFailed").
 *
 * The audit's reason ("a mandatory algorithm of every Java platform ... no environment can lack it") answers a different question:
 * SHA-256 is never missing, but it can FAIL. Under a FIPS-configured JDK `SunPKCS11` is the first provider and serves
 * `MessageDigest.SHA-256` from the token, and `P11Digest.engineDigest` reports a token error as `ProviderException("digest() failed")`
 * (JDK 21 `jdk.crypto.cryptoki/sun/security/pkcs11/P11Digest.java` :185-186) - exactly the shape A6-m1 fixed for AES-GCM.
 * `MessageDigest` has no fail-over once an engine is chosen. The digests a store call computes:
 *
 * - every round of every encrypted store, when the store file's name is long: `EncryptedFileGuard.locked` computes
 *   `CrossProcessLock.lockFileOf` (SHA-256 for a name of 251 bytes or more) and `temporaryPrefixOf` (SHA-256 above 232 bytes)
 *   BEFORE its `shielded` block (phase A primitives, called outside any catch);
 * - every operation of an OS-key store: `StoreIdentity.of` (SHA-256 of the directory) and `headerFingerprint`, inside the block,
 *   where `shielded` maps only `IOException`, `SecurityException`, `IllegalStateException` and `IllegalArgumentException`.
 *
 * The stand-in: a provider at the first position whose SHA-256 delegates to the SUN provider and fails only for an input that
 * contains this test's marker (the store's unique name or directory), so nothing else in the JVM is affected. Removed in `finally`.
 */
class StoreDigestA7BreakerR7Test {

    private fun <T> withFailingDigestFor(marker: String, block: () -> T): T {
        val provider = A7TokenDigestProvider()
        A7TokenDigestSpi.marker = marker.toByteArray(StandardCharsets.UTF_8)
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            A7TokenDigestSpi.marker = null
            Security.removeProvider(provider.name)
        }
    }

    private fun assertTyped(label: String, outcome: Result<StorageResult<*>>) {
        assertTrue(
            outcome.isSuccess,
            "$label: a failing SHA-256 provider (a PKCS#11 token error under FIPS) escaped the store call as " +
                "${outcome.exceptionOrNull()?.let { it::class.java.name }}: ${outcome.exceptionOrNull()?.message}",
        )
        val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
        assertIs<StorageError.OperationFailed>(error, "$label: a provider failure is OperationFailed: $error")
    }

    /**
     * An app-key store whose file name is 253 bytes (a 224-byte store name): its lock and temporary names are hashed on every round.
     * Control: without the failing digest the same store writes and reads, and the stand-in was asked for the marker.
     */
    @Test
    fun `an app-key store with a long name answers a failing SHA-256 provider with OperationFailed`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val name: String = "a7digest-" + "x".repeat(224 - "a7digest-".length)
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                assertEquals(253, file.name.toByteArray(StandardCharsets.UTF_8).size, "control: the file name is long enough to be hashed")
                val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 6 } }, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store works")
                val before: ByteArray = file.readBytes()
                val asked: Int = A7TokenDigestSpi.failures.get()
                val answers: List<Pair<String, Result<StorageResult<*>>>> = withFailingDigestFor(name) {
                    runBlocking { listOf("get" to runCatching { store.get("k") }, "put" to runCatching { store.put("k2", "v2") }) }
                }
                assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the store computed a digest of its name")
                for ((label: String, outcome: Result<StorageResult<*>>) in answers) assertTyped("app-key $label", outcome)
                assertContentEquals(before, file.readBytes(), "the failed calls changed the store file")
                assertEquals(StorageResult.Success("v"), store.get("k"), "control: the same instance reads once the provider works")
            }
        }
        Unit
    }

    /**
     * An OS-key store (fake binding) of an ordinary name: every operation computes the identity of its directory. Control: the
     * store works before and after, and the stand-in was asked for the marker.
     */
    @Test
    fun `an OS-key store answers a failing SHA-256 provider with OperationFailed`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val store: EncryptedKeyValueStorage = rig.store()
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store works")
                val before: ByteArray = rig.dataFile().readBytes()
                val asked: Int = A7TokenDigestSpi.failures.get()
                val answers: List<Pair<String, Result<StorageResult<*>>>> = withFailingDigestFor(rig.directory.name) {
                    runBlocking {
                        listOf(
                            "get" to runCatching { store.get("k") },
                            "put" to runCatching { store.put("k2", "v2") },
                            "remove" to runCatching { store.remove("k") },
                        )
                    }
                }
                assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the store computed a digest of its directory")
                for ((label: String, outcome: Result<StorageResult<*>>) in answers) assertTyped("OS-key $label", outcome)
                assertContentEquals(before, rig.dataFile().readBytes(), "the failed calls changed the store file")
                assertEquals(StorageResult.Success("v"), store.get("k"), "control: the same instance reads once the provider works")
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}

/** Serves `MessageDigest.SHA-256` through [A7TokenDigestSpi]. */
class A7TokenDigestProvider : Provider("KmpToolkitBreakerA7Digest", "1.0", "breaker A7: SHA-256 that fails like a PKCS#11 token") {
    init {
        put("MessageDigest.SHA-256", A7TokenDigestSpi::class.java.name)
    }
}

/** SHA-256 of the SUN provider; a digest whose input contains [marker] fails as `P11Digest` reports a token error. */
class A7TokenDigestSpi : MessageDigestSpi() {
    private val real: MessageDigest = MessageDigest.getInstance("SHA-256", checkNotNull(Security.getProvider("SUN")) { "control: SUN exists" })
    private val seen = ByteArrayOutputStream()

    override fun engineGetDigestLength(): Int = 32

    override fun engineUpdate(input: Byte) {
        real.update(input)
        seen.write(input.toInt())
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        real.update(input, offset, len)
        seen.write(input, offset, len)
    }

    override fun engineDigest(): ByteArray {
        val input: ByteArray = seen.toByteArray()
        seen.reset()
        val failing: ByteArray? = marker
        if (failing != null && contains(input, failing)) {
            real.reset()
            failures.incrementAndGet()
            throw ProviderException("digest() failed (breaker A7 stand-in for a PKCS#11 token error)")
        }
        return real.digest()
    }

    override fun engineReset() {
        real.reset()
        seen.reset()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var match = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    companion object {
        @Volatile var marker: ByteArray? = null
        val failures: AtomicInteger = AtomicInteger()
    }
}
