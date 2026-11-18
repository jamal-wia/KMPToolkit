package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.UserNotAuthenticatedException
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JCE provider for [LateAuthKey]: `Cipher.init` always succeeds and the refusal for an unauthenticated user comes
 * from `doFinal`, as a `ProviderException` that carries the `UserNotAuthenticatedException`. It exists because the
 * platform may report that refusal at either step, and the production cipher must map both to the same answer. The
 * crypto is the JDK's own AES-GCM. Group sto-android, step 4e.
 */
internal object LateAuthGcmProvider : Provider("KmpToolkitLateAuthGcm", 1.0, "AES/GCM that refuses at doFinal for host tests") {

    init {
        putService(
            object : Service(this, "Cipher", "AES/GCM/NoPadding", LateAuthGcmCipher::class.java.name, null,
                mapOf("SupportedKeyClasses" to LateAuthKey::class.java.name)) {
                override fun newInstance(constructorParameter: Any?): Any = LateAuthGcmCipher()
            },
        )
    }

    fun install() {
        if (Security.getProvider(name) == null) Security.insertProviderAt(this, 1)
    }

    fun uninstall() {
        Security.removeProvider(name)
    }
}

/** Whether the user is authenticated: the one switch every [LateAuthKey] consults at `doFinal`. */
internal class AuthGate(open: Boolean = false) {
    @Volatile
    var open: Boolean = open
}

internal class LateAuthKey(val material: SecretKey, val gate: AuthGate) : SecretKey {
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

internal class LateAuthGcmCipher : CipherSpi() {
    private val inner: Cipher = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")
    private var gate: AuthGate? = null

    private fun unwrap(key: Key?): SecretKeySpec {
        val late: LateAuthKey = key as? LateAuthKey ?: throw InvalidKeyException("not a late-auth key")
        gate = late.gate
        return SecretKeySpec(late.material.encoded, "AES")
    }

    private fun checkAuthenticated() {
        if (gate?.open != true) throw ProviderException("authentication required", UserNotAuthenticatedException("user not authenticated"))
    }

    override fun engineSetMode(mode: String?) {}
    override fun engineSetPadding(padding: String?) {}
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = inner.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = inner.iv
    override fun engineGetParameters(): AlgorithmParameters? = inner.parameters

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        inner.init(opmode, unwrap(key))
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        inner.init(opmode, unwrap(key), params)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
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
        checkAuthenticated()
        return inner.doFinal(input, inputOffset, inputLen)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int {
        checkAuthenticated()
        return inner.doFinal(input, inputOffset, inputLen, output, outputOffset)
    }
}
