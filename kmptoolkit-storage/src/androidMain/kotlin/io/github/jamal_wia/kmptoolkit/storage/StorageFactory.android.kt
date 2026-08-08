package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Creates a plain [KeyValueStorage] backed by a private `SharedPreferences` file.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a [Context] and
 * iOS needs nothing — an `expect` signature would have to invent a common context type that this
 * toolkit does not have. Construct the store in your platform layer and hold it behind the common
 * [KeyValueStorage] interface; shared code takes the interface and never names this function.
 *
 * ```kotlin
 * val storage: KeyValueStorage = createKeyValueStorage(context)
 * ```
 *
 * Cheap to call and safe to call more than once: two instances over the same [config] see the same
 * data, because `SharedPreferences` itself is process-wide per file. There is nothing to release.
 *
 * @param context any `Context`. Only its application context is retained, so passing an Activity
 *   cannot leak it.
 * @param config which store to open. The default derives the file name from the app's own package
 *   name — see [StorageConfig].
 */
public fun createKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
): KeyValueStorage {
    val applicationContext: Context = context.applicationContext
    val name: String = config.name ?: applicationContext.packageName
    return AndroidKeyValueStorage(applicationContext.preferences(plainStoreId(name)))
}

/**
 * Creates a [SecureKeyValueStorage] whose values are AES-256-GCM encrypted under a key held in the
 * AndroidKeyStore.
 *
 * ```kotlin
 * val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(context)
 * ```
 *
 * The key is generated on first use and reused afterwards, under an alias derived from [config] —
 * so two stores with different names cannot read each other's values even though both keys live in
 * the same key store. Neither the key nor the alias is hardcoded by this library.
 *
 * No hardware backing is required or checked: on a device with a StrongBox or a TEE the platform
 * uses it, and on one without, the same API falls back to a software-isolated key. The library does
 * not fail on the weaker device, because refusing to store a token there would only push the
 * consumer into storing it somewhere worse.
 *
 * A value can become permanently unreadable — see [SecureKeyValueStorage] and
 * [StorageError.Undecryptable]. Handle that case; it is reachable on ordinary devices whose owner
 * changes the screen lock.
 *
 * @param context any `Context`. Only its application context is retained.
 * @param config which store to open, and the alias its key is filed under — see [StorageConfig].
 */
public fun createSecureKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
): SecureKeyValueStorage {
    val applicationContext: Context = context.applicationContext
    val name: String = config.name ?: applicationContext.packageName
    return AndroidSecureKeyValueStorage(
        preferences = applicationContext.preferences(secureStoreId(name)),
        cipher = KeystoreValueCipher(AndroidKeystoreKeySource(secureKeyAlias(name))),
    )
}

private fun Context.preferences(fileName: String): SharedPreferences =
    getSharedPreferences(fileName, Context.MODE_PRIVATE)
