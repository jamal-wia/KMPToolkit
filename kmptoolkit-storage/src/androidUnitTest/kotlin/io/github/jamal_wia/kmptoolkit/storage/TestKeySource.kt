package io.github.jamal_wia.kmptoolkit.storage

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * An AES-256 key from the JVM's own provider, standing in for one from the AndroidKeyStore.
 *
 * Robolectric does not register an `AndroidKeyStore` provider — `KeyStore.getInstance` fails with
 * `KeyStoreException: AndroidKeyStore not found` — so the production [AndroidKeystoreKeySource]
 * cannot run off a device. Substituting only the key *source* keeps everything that can actually
 * hold a bug under test: the IV handling, the GCM parameters, the Base64 framing, and every
 * behavior of [AndroidSecureKeyValueStorage] built on top of them run exactly as they do in
 * production. What is left untested on the JVM is the key-store lookup itself, and
 * [AndroidKeystoreKeySourceTest] covers the one thing that can be asserted about it here — that its
 * absence degrades to [StorageError.Unavailable] rather than to a crash.
 *
 * Keys are cached per name so that two stores built from the same [StorageConfig] share one key,
 * which is what a real key store gives them.
 */
internal object TestKeys {

    private val keys: MutableMap<String, SecretKey> = mutableMapOf()

    fun source(name: String): SecretKeySource = SecretKeySource {
        keys.getOrPut(name) { generate() }
    }

    /** A source that always fails, standing in for a key store that will not open. */
    fun unavailableSource(): SecretKeySource = SecretKeySource {
        throw StorageKeyUnavailableException(IllegalStateException("no key store"))
    }

    fun generate(): SecretKey = KeyGenerator.getInstance("AES")
        .apply { init(KEY_SIZE_BITS) }
        .generateKey()

    fun reset() {
        keys.clear()
    }

    private const val KEY_SIZE_BITS = 256
}
