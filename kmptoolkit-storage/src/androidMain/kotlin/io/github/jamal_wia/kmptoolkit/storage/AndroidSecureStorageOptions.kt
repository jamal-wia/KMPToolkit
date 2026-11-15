@file:MustUseReturnValues

package io.github.jamal_wia.kmptoolkit.storage

/**
 * The properties of the Android Keystore key behind a [SecureKeyValueStorage], passed to
 * `createSecureKeyValueStorage(context, config, options)`.
 *
 * ```kotlin
 * val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(
 *     context,
 *     StorageConfig("tokens"),
 *     AndroidSecureStorageOptions(keyBacking = AndroidKeyBacking.PREFER_STRONGBOX),
 * )
 * ```
 *
 * The options apply **when a key is generated**, which is the first use of a store on a device. A key that already
 * exists keeps the properties it was generated with: changing an option for an installed app changes nothing for the
 * existing key. The library has no call that regenerates it, because that would make every stored value
 * [StorageError.Undecryptable]. To get a key with new properties, open the store under another
 * [StorageConfig.name] (a new alias, a new key) and move the values you want to keep, or clear the app's data.
 *
 * A plain class, not a data class, so that a later option can be added without changing a generated `copy` or
 * `componentN`.
 *
 * @param keyBacking where the key must live, see [AndroidKeyBacking].
 * @param unlockedDeviceRequired the key can be used only while the device is unlocked
 *   (`setUnlockedDeviceRequired`, API 28 and newer; ignored below). While the device is locked every call that needs
 *   the key answers [StorageError.Unavailable]: retry after the user unlocks it. Reading an entry in a background
 *   service while the device is locked then fails, which is the point of the option; do not enable it for a store a
 *   background sync must read.
 */
public class AndroidSecureStorageOptions(
    public val keyBacking: AndroidKeyBacking = AndroidKeyBacking.DEFAULT,
    public val unlockedDeviceRequired: Boolean = false,
) {
    override fun toString(): String =
        "AndroidSecureStorageOptions(keyBacking=$keyBacking, unlockedDeviceRequired=$unlockedDeviceRequired)"
}

/**
 * Where an Android store's Keystore key is generated. May grow in a minor version, so give a `when` over it an `else`.
 */
public enum class AndroidKeyBacking {

    /** The platform default (a trusted execution environment, or software); never fails for lack of hardware. */
    DEFAULT,

    /**
     * A StrongBox secure element when the device has one, otherwise the default. Read the outcome with
     * [androidKeySecurityLevel].
     */
    PREFER_STRONGBOX,

    /**
     * A StrongBox secure element or nothing: on a device without one (and on every device below API 28) the first use
     * of the store answers [StorageError.Unsupported] with [UnsupportedReason.HARDWARE_UNAVAILABLE], and no key is
     * generated.
     */
    REQUIRE_STRONGBOX,
}

/** The security level the platform reports for a store's key. May grow in a minor version, so give a `when` an `else`. */
public enum class AndroidKeySecurityLevel {

    /** A software-backed key. */
    SOFTWARE,

    /** A trusted execution environment. */
    TRUSTED_ENVIRONMENT,

    /** A StrongBox secure element. */
    STRONGBOX,

    /**
     * The platform could not say: below API 31 it reports only whether the key is in secure hardware, which does not
     * tell a trusted environment from a StrongBox, so a hardware key there is `UNKNOWN`; or the level is one this
     * library does not know.
     */
    UNKNOWN,
}

/**
 * The security level the platform reports for this store's key, resolving (on a first run, creating) the key if it is
 * not resolved yet.
 *
 * Use it to see whether [AndroidKeyBacking.PREFER_STRONGBOX] got a StrongBox. Answers `Success(UNKNOWN)` for a store
 * this library's Android factory did not create (a test double, a decorator): that store is a legitimate
 * [SecureKeyValueStorage], so it is not an error.
 *
 * @return the level, or the same typed failure [EncryptedKeyValueStorage.ensureKey] gives when the key cannot be
 *   resolved.
 */
public suspend fun SecureKeyValueStorage.androidKeySecurityLevel(): StorageResult<AndroidKeySecurityLevel> =
    (this as? AndroidSecureKeyValueStorage)?.keySecurityLevel() ?: StorageResult.Success(AndroidKeySecurityLevel.UNKNOWN)
