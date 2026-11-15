package io.github.jamal_wia.kmptoolkit.storage

import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JCE provider that answers like the AndroidKeyStore provider for its own key class, [DeviceLikeKey], and is chosen
 * the same way the AndroidKeyStore provider is (by the key's class), so the production [KeystoreValueCipher] runs over
 * it unchanged. The crypto is the JDK's own AES-GCM; the one behaviour reproduced from the device is that a GCM
 * decryption whose input is shorter than the 16-byte tag fails with `IllegalBlockSizeException`
 * (`AndroidKeyStoreCipherSpiBase.engineDoFinal` maps every Keymaster error other than VERIFICATION_FAILED and
 * INVALID_ARGUMENT, here KM_ERROR_INVALID_INPUT_LENGTH, to it), where the JDK answers `AEADBadTagException`.
 *
 * Breaker review round 1 of group sto-android (ticket release-5-1-0).
 */
internal object DeviceLikeGcmProvider : Provider("KmpToolkitDeviceLikeGcm", 1.0,"AndroidKeyStore-like AES/GCM for host tests") {

    init {
        putService(
            object : Service(this, "Cipher", "AES/GCM/NoPadding", DeviceLikeGcmCipher::class.java.name, null,
                mapOf("SupportedKeyClasses" to DeviceLikeKey::class.java.name)) {
                override fun newInstance(constructorParameter: Any?): Any = DeviceLikeGcmCipher()
            },
        )
    }

    /** How many ciphers of this provider were initialised: the proof that the production cipher went through it. */
    val initialisations: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

    /** Installs the provider ahead of every other one; idempotent. */
    fun install() {
        if (Security.getProvider(name) == null) Security.insertProviderAt(this, 1)
    }

    fun uninstall() {
        Security.removeProvider(name)
    }
}

/** A key only [DeviceLikeGcmProvider] can use: like an AndroidKeyStore handle, it exposes no key material. */
internal class DeviceLikeKey(val material: SecretKey) : SecretKey {
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

internal class DeviceLikeGcmCipher : CipherSpi() {
    private val inner: Cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")
    private var decrypting: Boolean = false

    private fun unwrap(key: Key?): SecretKeySpec {
        val device: DeviceLikeKey = key as? DeviceLikeKey ?: throw java.security.InvalidKeyException("not a device key")
        DeviceLikeGcmProvider.initialisations.incrementAndGet()
        return SecretKeySpec(device.material.encoded, "AES")
    }

    override fun engineSetMode(mode: String?) {}
    override fun engineSetPadding(padding: String?) {}
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = inner.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = inner.iv
    override fun engineGetParameters(): AlgorithmParameters? = inner.parameters

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        decrypting = opmode == Cipher.DECRYPT_MODE
        inner.init(opmode, unwrap(key))
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        decrypting = opmode == Cipher.DECRYPT_MODE
        inner.init(opmode, unwrap(key), params)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
        decrypting = opmode == Cipher.DECRYPT_MODE
        inner.init(opmode, unwrap(key), params)
    }

    override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) {
        inner.updateAAD(src, offset, len)
    }

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? =
        inner.update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        inner.update(input, inputOffset, inputLen, output, outputOffset)

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray {
        if (decrypting && inputLen < TAG_BYTES) throw IllegalBlockSizeException("KM_ERROR_INVALID_INPUT_LENGTH")
        return inner.doFinal(input, inputOffset, inputLen)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int {
        if (decrypting && inputLen < TAG_BYTES) throw IllegalBlockSizeException("KM_ERROR_INVALID_INPUT_LENGTH")
        return inner.doFinal(input, inputOffset, inputLen, output, outputOffset)
    }

    private companion object {
        const val TAG_BYTES = 16
    }
}
