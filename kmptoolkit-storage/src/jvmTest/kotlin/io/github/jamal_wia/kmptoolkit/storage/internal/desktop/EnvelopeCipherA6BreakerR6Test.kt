package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.KeySpec
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactorySpi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Group sto-desktop, phase A breaker round 6 (decisions.md D-23), part A6: the round-7 fix 1f50e707 of the phase A crypto
 * primitives (format v1, plan 4.3: `EnvelopeCipher`, `Pbkdf2`) against their own KDoc:
 *
 * - `EnvelopeCipher`: "A tag that does not verify is the only failure that answers 'wrong key or tampered' (null / false); any
 *   other security-provider failure, on encryption and on decryption alike, is thrown as an IllegalStateException, so an
 *   environment problem is never mistaken for lost data ... and the stores answer it as OperationFailed";
 * - `Pbkdf2.derive`: "@throws IllegalStateException when the JDK has no PBKDF2 provider".
 *
 * The round-7 fix wraps `GeneralSecurityException` only. A provider's engine reports a failure of the provider itself (a PKCS#11
 * token error under a FIPS-configured JDK, where `SunPKCS11` serves AES-GCM and PBKDF2) as `java.security.ProviderException`,
 * a `RuntimeException` that is no `GeneralSecurityException`. Here a provider at the first position whose engines throw it stands
 * in for that environment. Every store guard maps `IllegalStateException` (and `IOException`) only, so what leaves the primitive
 * leaves the store call. `Pbkdf2.derive` is pinned too: the JDK's `SecretKeyFactory` fails over to the next provider and wraps a
 * lone provider's failure in `InvalidKeySpecException` (a `GeneralSecurityException`), so it is covered; `Cipher` has no such
 * fail-over once it is initialised, so the AES-GCM failure leaves as it is. The provider is inserted for the duration of each call and removed in `finally`; the control shows
 * the primitives work again afterwards.
 */
class EnvelopeCipherA6BreakerR6Test {

    private fun <T> withFailingProvider(block: () -> T): T {
        val provider = A6FailingProvider()
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the failing provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    private fun expectIllegalState(what: String, block: () -> Any?) {
        val outcome: Result<Any?> = runCatching(block)
        val thrown: Throwable = outcome.exceptionOrNull()
            ?: fail("$what: a failing provider must be thrown as IllegalStateException, never answered (answer: ${outcome.getOrNull()?.let { it::class.simpleName }})")
        val illegalState: IllegalStateException = assertIs<IllegalStateException>(
            thrown,
            "$what: KDoc 'any other security-provider failure ... is thrown as an IllegalStateException' (the stores map only " +
                "that to OperationFailed); a ${thrown::class.java.name} would leave the store call untyped",
        )
        assertNotNull(illegalState.cause, "$what: the provider failure is the cause")
    }

    @Test
    fun `gcmEncrypt throws IllegalStateException for a provider failure that is not a GeneralSecurityException`() {
        val key = ByteArray(32) { 7 }
        withFailingProvider {
            expectIllegalState("gcmEncrypt") { EnvelopeCipher.gcmEncrypt(key, ByteArray(12), ByteArray(0), ByteArray(4)) }
        }
        // Control: with the provider gone the primitive works.
        assertEquals(20, EnvelopeCipher.gcmEncrypt(key, ByteArray(12), ByteArray(0), ByteArray(4)).size)
    }

    @Test
    fun `gcmDecrypt throws IllegalStateException and never answers wrong key for a provider failure`() {
        val key = ByteArray(32) { 7 }
        val sealed: ByteArray = EnvelopeCipher.gcmEncrypt(key, ByteArray(12), ByteArray(0), ByteArray(4))
        withFailingProvider {
            expectIllegalState("gcmDecrypt") { EnvelopeCipher.gcmDecrypt(key, ByteArray(12), ByteArray(0), sealed) }
        }
        assertEquals(4, EnvelopeCipher.gcmDecrypt(key, ByteArray(12), ByteArray(0), sealed)?.size, "control: it decrypts again")
    }

    @Test
    fun `Pbkdf2 derive never lets a provider failure out untyped, alone or with a fallback provider`() {
        // With SunJCE behind it, the JDK's SecretKeyFactory fails over to the next provider: an answer, never a throw.
        val withFallback: Result<ByteArray> = withFailingProvider { runCatching { Pbkdf2.derive("pass".toCharArray(), ByteArray(16), 1, 32) } }
        withFallback.exceptionOrNull()?.let { assertIs<IllegalStateException>(it, "Pbkdf2.derive let ${it::class.java.name} out untyped") }
        // Alone (a FIPS-like set: the failing provider is the only PBKDF2 there is): IllegalStateException with a cause.
        val sunJce: Provider = Security.getProvider("SunJCE") ?: error("control: this JDK has SunJCE")
        val position: Int = Security.getProviders().indexOf(sunJce) + 1
        Security.removeProvider("SunJCE")
        try {
            withFailingProvider {
                expectIllegalState("Pbkdf2.derive (only the failing provider)") { Pbkdf2.derive("pass".toCharArray(), ByteArray(16), 1, 32) }
            }
        } finally {
            Security.insertProviderAt(sunJce, position)
        }
        assertEquals(32, Pbkdf2.derive("pass".toCharArray(), ByteArray(16), 1, 32).size, "control: it derives again")
    }
}

/** A provider whose AES-GCM and PBKDF2 engines fail the way a broken PKCS#11 token does (`ProviderException`). */
class A6FailingProvider : Provider("KmpToolkitBreakerA6Failing", "1.0", "breaker A6: engines that throw ProviderException") {
    init {
        put("Cipher.AES/GCM/NoPadding", A6FailingGcmSpi::class.java.name)
        put("SecretKeyFactory.PBKDF2WithHmacSHA256", A6FailingPbkdf2Spi::class.java.name)
    }
}

private fun a6TokenFailure(): ProviderException = ProviderException("token failure (breaker A6 provider)")

/** Initialises like any AES-GCM engine and fails in the operation itself. */
class A6FailingGcmSpi : CipherSpi() {
    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = inputLen + 16
    override fun engineGetIV(): ByteArray? = null
    override fun engineGetParameters(): AlgorithmParameters? = null
    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) = Unit
    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) = Unit
    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) = Unit
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = throw a6TokenFailure()
    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        throw a6TokenFailure()
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = throw a6TokenFailure()
    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        throw a6TokenFailure()
}

/** A PBKDF2 factory that fails in the derivation itself. */
class A6FailingPbkdf2Spi : SecretKeyFactorySpi() {
    override fun engineGenerateSecret(keySpec: KeySpec?): SecretKey = throw a6TokenFailure()
    override fun engineGetKeySpec(key: SecretKey?, keySpec: Class<*>?): KeySpec = throw a6TokenFailure()
    override fun engineTranslateKey(key: SecretKey?): SecretKey = throw a6TokenFailure()
}
