package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.JvmKeyValueStorage
import java.io.File
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.SecureRandomSpi
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers

/**
 * Group sto-desktop, round 9: the JCA boundary [Jca] (decisions D-P12), the one place every `Cipher`, `SecretKeyFactory`,
 * `SecureRandom`, `MessageDigest` and `Base64` use of the storage module goes through. Derived from its KDoc, three rules:
 *
 * 1. whatever a provider throws, except an `Error`, is one [JcaFailure] with the original as the cause (the JCA reports failures
 *    in different shapes per engine, and rounds 5 to 7 each found one more);
 * 2. only a GCM tag that does not verify answers `null` (wrong key or tampered): a bad-tag exception WITH a cause is a provider
 *    failure;
 * 3. an `Error` (`InternalError` from the DRBG's seed source) is never caught.
 *
 * The stand-in providers are the breaker's (`A7TokenGcmProvider`, `A7TokenDigestProvider`) plus two random sources of this class;
 * each is inserted for one call and removed in `finally`, and every case has a control that the same primitive works without it.
 */
class JcaBoundaryRound9Test {

    private val key: ByteArray = ByteArray(32) { index: Int -> (index + 1).toByte() }
    private val nonce: ByteArray = ByteArray(12) { index: Int -> (index + 40).toByte() }
    private val aad: ByteArray = "aad".toByteArray(Charsets.UTF_8)
    private val plain: ByteArray = "plain".toByteArray(Charsets.UTF_8)

    private fun <T> withProvider(provider: Provider, block: () -> T): T {
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            A7TokenGcmSpi.failDecrypt.set(false)
            A7TokenGcmSpi.failEncryptWithNpe.set(false)
            A7TokenDigestSpi.marker = null
            Security.removeProvider(provider.name)
        }
    }

    private fun assertTyped(what: String, thrown: Throwable?, causeType: Class<out Throwable>) {
        val typed: JcaFailure = assertIs<JcaFailure>(thrown ?: fail("$what: a failing provider answered instead of throwing"), "$what: $thrown")
        assertIs<IllegalStateException>(typed, "$what: every store guard maps IllegalStateException")
        val cause: Throwable = assertNotNull(typed.cause, "$what: the provider's own exception is the cause")
        assertEquals(causeType, cause::class.java, "$what: the cause is the provider's exception, unchanged")
    }

    /** Controls and the one tag decision: a round trip, and a real mismatch (a flipped bit, a wrong key, a wrong AAD) answers `null`, never an exception. */
    @Test
    fun `a real tag mismatch is null and nothing else is`() {
        val sealed: ByteArray = Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain)
        assertContentEquals(plain, Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed), "control: the round trip works")
        val flipped: ByteArray = sealed.copyOf().also { bytes: ByteArray -> bytes[0] = (bytes[0].toInt() xor 1).toByte() }
        assertNull(Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, flipped), "a flipped bit")
        assertNull(Jca.aesGcmOpen(ByteArray(32) { 9 }, nonce, aad, TAG_BITS, sealed), "a wrong key")
        assertNull(Jca.aesGcmOpen(key, nonce, "other".toByteArray(Charsets.UTF_8), TAG_BITS, sealed), "a wrong AAD")
    }

    /** Rule 2: a bad-tag exception that carries a cause (a PKCS#11 token error) is a provider failure, even though it is an `AEADBadTagException`. */
    @Test
    fun `a bad tag exception with a cause is a provider failure`() {
        val sealed: ByteArray = Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain)
        withProvider(A7TokenGcmProvider()) {
            val before: Int = A7TokenGcmSpi.decrypts.get()
            A7TokenGcmSpi.failDecrypt.set(true)
            val thrown: Throwable? = runCatching { Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed) }.exceptionOrNull()
            assertTrue(A7TokenGcmSpi.decrypts.get() > before, "control: the stand-in engine was the one asked")
            assertTyped("token error as a tag failure", thrown, javax.crypto.AEADBadTagException::class.java)
            A7TokenGcmSpi.failDecrypt.set(false)
            assertContentEquals(plain, Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed), "control: the same engine decrypts when it does not fail")
        }
    }

    /** Rule 1: plain noise from a broken engine is typed too, not only the three JCA shapes. */
    @Test
    fun `a plain runtime exception of an engine is typed`() {
        withProvider(A7TokenGcmProvider()) {
            A7TokenGcmSpi.failEncryptWithNpe.set(true)
            assertTyped("sealing", runCatching { Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain) }.exceptionOrNull(), NullPointerException::class.java)
            A7TokenGcmSpi.failEncryptWithNpe.set(false)
            assertEquals(plain.size + 16, Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain).size, "control: the engine seals when it does not fail")
        }
    }

    /** Rule 1 for the random source (a `ProviderException` and plain noise are typed), rule 3 for its `Error`. */
    @Test
    fun `a failing random source is typed except for an Error`() {
        val provider = JcaRoundNineRandomProvider()
        assertEquals(16, Jca.randomBytes(Jca.newRandom(), 16).size, "control: the default source draws")
        withProvider(provider) {
            val providerFailure: SecureRandom = SecureRandom.getInstance("JcaR9ProviderFailure", provider)
            assertTyped("a ProviderException source", runCatching { Jca.randomBytes(providerFailure, 16) }.exceptionOrNull(), ProviderException::class.java)
            val noise: SecureRandom = SecureRandom.getInstance("JcaR9Noise", provider)
            assertTyped("a plain RuntimeException source", runCatching { Jca.randomBytes(noise, 16) }.exceptionOrNull(), IllegalArgumentException::class.java)
            val fatal: SecureRandom = SecureRandom.getInstance("JcaR9Fatal", provider)
            // The decision: an Error is a VirtualMachineError, never caught by the library.
            val thrown: Throwable? = runCatching { Jca.randomBytes(fatal, 16) }.exceptionOrNull()
            assertIs<InternalError>(thrown, "an InternalError of the DRBG's seed source is not typed: $thrown")
        }
    }

    /** SHA-256 is exact whichever provider serves it, and a failing one is typed (the digests address OS items and file names). */
    @Test
    fun `sha256 is exact and a failing provider is typed`() {
        val abc: ByteArray = "abc".toByteArray(Charsets.UTF_8)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Jca.sha256(abc).joinToString("") { "%02x".format(it) }, "control: the standard vector")
        withProvider(A7TokenDigestProvider()) {
            A7TokenDigestSpi.marker = "abc".toByteArray(Charsets.UTF_8)
            val asked: Int = A7TokenDigestSpi.failures.get()
            assertTyped("sha256", runCatching { Jca.sha256(abc) }.exceptionOrNull(), ProviderException::class.java)
            assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the stand-in digest was the one asked")
            assertEquals(32, Jca.sha256("other".toByteArray(Charsets.UTF_8)).size, "control: another input is served by the same provider")
        }
    }

    /**
     * The decision for the plain store: it computes its lock and temporary-file names when it is made, and hashes only a file name
     * of 232 bytes or more, so a failing SHA-256 provider with such a name fails the construction (the factory), typed, never as a
     * raw `ProviderException`. A shorter name needs no digest at all (control). Construction is not a store call.
     */
    @Test
    fun `a plain store with a long name and a failing SHA-256 provider fails its construction with a typed failure`() {
        val longName: String = "plain-r9-" + "x".repeat(240 - "plain-r9-".length)
        assertEquals(240, longName.toByteArray(Charsets.UTF_8).size, "control: the name is long enough to be hashed")
        JvmKeyValueStorage(File(longName.take(100)), Dispatchers.IO)
        withProvider(A7TokenDigestProvider()) {
            A7TokenDigestSpi.marker = longName.toByteArray(Charsets.UTF_8)
            val asked: Int = A7TokenDigestSpi.failures.get()
            val thrown: Throwable? = runCatching { JvmKeyValueStorage(File(longName), Dispatchers.IO) }.exceptionOrNull()
            assertTrue(A7TokenDigestSpi.failures.get() > asked, "control: the stand-in digest was asked for the long name")
            assertTyped("plain store construction", thrown, ProviderException::class.java)
            A7TokenDigestSpi.marker = null
            JvmKeyValueStorage(File(longName), Dispatchers.IO)
        }
    }

    /** The callers' own argument checks are outside the provider catch and keep their types: a bad parameter is not an environment problem. */
    @Test
    fun `a bad argument keeps its own type`() {
        assertFailsWith<IllegalArgumentException>("an empty salt") { Jca.pbkdf2Sha256("pw".toCharArray(), ByteArray(0), 1000, 32) }
        val derived: ByteArray = Jca.pbkdf2Sha256("pw".toCharArray(), ByteArray(16), 1000, 32)
        assertEquals(32, derived.size, "control: a good derivation works")
    }

    /** The base64 text of a Linux secret: exactly the expected size or `null`, never an exception, never a partial result. */
    @Test
    fun `base64 decodes exactly the expected size or null`() {
        val keyBytes = ByteArray(32) { index: Int -> (index * 7 + 1).toByte() }
        val text: ByteArray = Jca.base64Encode(keyBytes)
        assertContentEquals(keyBytes, Jca.base64DecodeExactly(text, 32), "control: the round trip")
        assertNull(Jca.base64DecodeExactly(text + '\n'.code.toByte(), 32), "a trailing newline (as `secret-tool store` makes it)")
        assertNull(Jca.base64DecodeExactly(Jca.base64Encode(ByteArray(31)), 32), "31 bytes")
        assertNull(Jca.base64DecodeExactly(Jca.base64Encode(ByteArray(33)), 32), "33 bytes")
        assertNull(Jca.base64DecodeExactly(ByteArray(0), 32), "empty")
        assertNull(Jca.base64DecodeExactly("not base64 at all!".toByteArray(Charsets.UTF_8), 32), "garbage")
        assertNull(Jca.base64DecodeExactly(text.copyOf(text.size - 2), 32), "truncated by two characters (31 bytes; the JDK accepts a missing padding, so one character is still 32)")
    }

    private companion object {
        const val TAG_BITS: Int = 128
    }
}

/** Serves three random sources that fail in three shapes: `ProviderException`, a plain `IllegalArgumentException`, `InternalError`. */
class JcaRoundNineRandomProvider : Provider("KmpToolkitJcaR9Random", "1.0", "round 9: random sources that fail") {
    init {
        put("SecureRandom.JcaR9ProviderFailure", JcaR9ProviderFailureRandomSpi::class.java.name)
        put("SecureRandom.JcaR9Noise", JcaR9NoiseRandomSpi::class.java.name)
        put("SecureRandom.JcaR9Fatal", JcaR9FatalRandomSpi::class.java.name)
    }
}

/** Fails like NativePRNG when `/dev/random` cannot be read. */
class JcaR9ProviderFailureRandomSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray?) = Unit
    override fun engineNextBytes(bytes: ByteArray?): Unit = throw ProviderException("cannot read the entropy source (round 9)")
    override fun engineGenerateSeed(numBytes: Int): ByteArray = throw ProviderException("cannot read the entropy source (round 9)")
}

/** A broken engine's noise: not a JCA exception at all. */
class JcaR9NoiseRandomSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray?) = Unit
    override fun engineNextBytes(bytes: ByteArray?): Unit = throw IllegalArgumentException("noise (round 9)")
    override fun engineGenerateSeed(numBytes: Int): ByteArray = throw IllegalArgumentException("noise (round 9)")
}

/** Fails like the DRBG's `SeedGenerator` when its device cannot be read. */
class JcaR9FatalRandomSpi : SecureRandomSpi() {
    override fun engineSetSeed(seed: ByteArray?) = Unit
    override fun engineNextBytes(bytes: ByteArray?): Unit = throw InternalError("seed source unreadable (round 9)")
    override fun engineGenerateSeed(numBytes: Int): ByteArray = throw InternalError("seed source unreadable (round 9)")
}
