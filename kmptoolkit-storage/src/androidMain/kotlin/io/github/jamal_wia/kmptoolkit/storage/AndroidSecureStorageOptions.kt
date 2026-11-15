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
 * @param userAuthentication time-bound user authentication for the key, or `null` (the default) for none; see
 *   [AndroidUserAuthentication]. Needs a secure lock screen on the device.
 */
public class AndroidSecureStorageOptions(
    public val keyBacking: AndroidKeyBacking = AndroidKeyBacking.DEFAULT,
    public val unlockedDeviceRequired: Boolean = false,
    public val userAuthentication: AndroidUserAuthentication? = null,
) {
    override fun toString(): String =
        "AndroidSecureStorageOptions(keyBacking=$keyBacking, unlockedDeviceRequired=$unlockedDeviceRequired, " +
            "userAuthentication=$userAuthentication)"
}

/**
 * Time-bound user authentication for the Keystore key behind a store: after the user has authenticated (a biometric or
 * the device credential), the key can be used for [validitySeconds]; outside that window every call that needs the key
 * answers [StorageError.AccessDenied].
 *
 * ```kotlin
 * val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(
 *     context,
 *     StorageConfig("tokens"),
 *     AndroidSecureStorageOptions(userAuthentication = AndroidUserAuthentication(validitySeconds = 30)),
 * )
 * // The app shows its own BiometricPrompt (or the confirm-credential screen). When the user succeeds, the store works
 * // for the next 30 seconds; otherwise the calls answer AccessDenied and the app prompts again.
 * ```
 *
 * **The library never shows a prompt.** It has no UI and no `Activity`: the app authenticates the user with the platform's
 * own `BiometricPrompt` or `KeyguardManager` flow, and the platform then opens the window for the key. A call outside it
 * is [StorageError.AccessDenied] (a retry prompts again: retry only after the app has authenticated the user, never in a
 * loop, and never delete anything in reaction to it); [EncryptedKeyValueStorage.ensureKey] is the probe that tells
 * whether the window is open. Only the calls that use the key need it: reading an absent key, `remove` and `clear` use no
 * key and answer without authentication.
 *
 * Like every key option this applies **when the key is generated**; an existing key keeps the properties it has (a store
 * that is opened with this option after it was first used without it keeps its old key, and keeps answering as before).
 * The key is generated on the first use after a permanent invalidation too, and generating it needs a secure lock screen:
 * without one the call answers [StorageError.Unavailable] until the user sets one. The platform invalidates the key for
 * good when the lock screen is removed or the enrolled biometrics change; the store then replaces it as it replaces any
 * permanently invalidated key, and the values of the old key are [StorageError.Undecryptable].
 *
 * Do not combine it with [AndroidSecureStorageOptions.unlockedDeviceRequired] unless both are wanted: the platform
 * reports a locked device and an unauthenticated user the same way, so a store with both answers `AccessDenied` for
 * either.
 *
 * Not observed on a device: the validity window, the invalidation by enrolment and the prompt flow need a real
 * Keystore and a lock screen; the host tests cover the mapping, the key parameters and the lifecycle through seams.
 *
 * @param validitySeconds how long the key can be used after the user authenticated, at least 1. Per-use authentication
 *   (a `BiometricPrompt` crypto object for every operation) is not offered: it cannot work without a UI of the library's.
 * @param allowDeviceCredential `true` (the default): a strong biometric or the device credential (PIN, pattern,
 *   password) authenticates. `false`: only a strong biometric does, which needs API 30; on an older device the store
 *   refuses to generate the key and answers [StorageError.Unsupported] with [UnsupportedReason.HARDWARE_UNAVAILABLE]
 *   instead of silently accepting the device credential too.
 * @throws IllegalArgumentException if [validitySeconds] is less than 1.
 */
public class AndroidUserAuthentication(
    public val validitySeconds: Int,
    public val allowDeviceCredential: Boolean = true,
) {
    init {
        require(validitySeconds >= 1) { "validitySeconds must be at least 1, was $validitySeconds" }
    }

    override fun toString(): String =
        "AndroidUserAuthentication(validitySeconds=$validitySeconds, allowDeviceCredential=$allowDeviceCredential)"
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
     * A StrongBox secure element or nothing, *when the key is generated*: on a device without one (and on every device
     * below API 28) the first use of the store answers [StorageError.Unsupported] with
     * [UnsupportedReason.HARDWARE_UNAVAILABLE], and no key is generated. An alias that already holds a key keeps it,
     * StrongBox or not, and answers no `Unsupported`: that is the case when an app switches from [DEFAULT], or when
     * another store with the same [StorageConfig.name] was opened with other options earlier in the process (the key is
     * cached per alias, not per options). Check [androidKeySecurityLevel].
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
