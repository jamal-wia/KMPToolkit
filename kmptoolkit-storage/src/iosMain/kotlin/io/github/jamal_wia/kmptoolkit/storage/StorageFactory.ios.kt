package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly

/**
 * Creates a plain [KeyValueStorage] backed by a named `NSUserDefaults` suite.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a `Context` and
 * iOS needs nothing — an `expect` signature would have to invent a common context type that this
 * toolkit does not have. Construct the store in your platform layer and hold it behind the common
 * [KeyValueStorage] interface; shared code takes the interface and never names this function.
 *
 * ```kotlin
 * val storage: KeyValueStorage = createKeyValueStorage()
 * ```
 *
 * Cheap to call and safe to call more than once: two instances over the same [config] see the same
 * data, because `NSUserDefaults` is process-wide per suite. There is nothing to release.
 *
 * @param config which store to open. The default derives the suite name from the app's own
 *   `CFBundleIdentifier` — see [StorageConfig].
 */
public fun createKeyValueStorage(config: StorageConfig = StorageConfig()): KeyValueStorage {
    val suiteName: String = plainStoreId(config.name ?: bundleIdentifier())
    return IosKeyValueStorage(
        suiteName = suiteName,
        // The suite name can never equal the bundle identifier — plainStoreId always appends a
        // suffix — so this never silently resolves to the standard defaults, which is what
        // NSUserDefaults(suiteName:) does when handed the bundle id and which would make clear()
        // destructive. The Kotlin/Native constructor is non-null (the Objective-C initializer
        // returning nil surfaces as an exception, not as a null), so there is no fallback here.
        defaults = NSUserDefaults(suiteName = suiteName),
        dispatcher = Dispatchers.IO,
    )
}

/**
 * Creates a [SecureKeyValueStorage] backed by the iOS Keychain.
 *
 * ```kotlin
 * val secrets: SecureKeyValueStorage = createSecureKeyValueStorage()
 * ```
 *
 * Items are filed under a `kSecAttrService` derived from [config], so two stores with different
 * names never see each other's values. Nothing about the service string is hardcoded by this
 * library. The factory never touches the Keychain: it cannot fail on the device's state, and the first
 * Keychain call happens with the first operation.
 *
 * Keychain items **outlive an app uninstall** on iOS — reinstalling the app can hand you a token
 * written by the previous install. That is the platform's behavior, not this module's; if it
 * matters, call [KeyValueStorage.clear] on first run, keyed off a flag in the plain store, which
 * *is* removed with the app.
 *
 * [KeyValueStorage.clear] removes this store's items, local and synchronizable, in exactly one access
 * group: [IosSecureStorageOptions.accessGroup] when it is set, otherwise the app's default access group, so
 * it never touches a same-named store that lives in a shared group. `get`, `put` and `remove` of a store with
 * `accessGroup = null` are not pinned that way and can reach a same-named store in a shared group: a known limit,
 * described at [IosSecureStorageOptions.accessGroup].
 *
 * @param config which store to open — see [StorageConfig].
 * @param options the Keychain attributes of the store: accessibility, access group, iCloud
 *   synchronization and user presence. The defaults keep the items readable by background work after the first
 *   unlock following a reboot, out of any backup, and out of iCloud. A group your entitlements do not grant
 *   makes every operation fail with [StorageError.Unsupported] ([UnsupportedReason.MISSING_ENTITLEMENT]); the
 *   library cannot check entitlements for you. See [IosSecureStorageOptions].
 */
public fun createSecureKeyValueStorage(
    config: StorageConfig = StorageConfig(),
    options: IosSecureStorageOptions = IosSecureStorageOptions(),
): SecureKeyValueStorage = IosSecureKeyValueStorage(
    service = secureStoreId(config.name ?: bundleIdentifier()),
    options = options,
    dispatcher = Dispatchers.IO,
)

/**
 * When iOS may decrypt a Keychain item — the `kSecAttrAccessible` attribute.
 *
 * It is applied on every write, an update of an existing item included, so a changed value reaches
 * each item on its next `put`. A key that is never written again keeps the class it was stored with;
 * clear the store if you need the change to apply to everything at once.
 *
 * `ThisDeviceOnly` variants are excluded from encrypted backups and from device-to-device transfer.
 * That is usually what you want for a token: a secret that migrates to a new phone is a secret the
 * user cannot revoke by wiping the old one, and the app can obtain a fresh one by signing in.
 */
public enum class KeychainAccessibility {

    /**
     * Readable only while the device is unlocked. Backed up. The strictest option that still allows
     * a foreground app to work normally; background work — a push handler, a background fetch —
     * cannot read these items.
     */
    WHEN_UNLOCKED,

    /** As [WHEN_UNLOCKED], and excluded from backups and device transfer. */
    WHEN_UNLOCKED_THIS_DEVICE_ONLY,

    /** Readable after the first unlock following a reboot, including in the background. Backed up. */
    AFTER_FIRST_UNLOCK,

    /**
     * As [AFTER_FIRST_UNLOCK], and excluded from backups and device transfer. The default: it is
     * what a token that a background refresh has to read needs, without letting that token leave
     * the device it was issued for.
     */
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,

    /**
     * Readable while unlocked, and only on a device that has a passcode set — removing the passcode
     * deletes these items outright. Never backed up. For secrets that must not exist on an
     * unprotected device. The items are gone, not unreadable: afterwards a read answers
     * [StorageResult.Success] with `null`, exactly as for a key that was never written, so be ready to
     * re-acquire the value.
     */
    WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
}

@OptIn(ExperimentalForeignApi::class)
internal val KeychainAccessibility.constant: CFTypeRef?
    get() = when (this) {
        KeychainAccessibility.WHEN_UNLOCKED -> kSecAttrAccessibleWhenUnlocked
        KeychainAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY ->
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly

        KeychainAccessibility.AFTER_FIRST_UNLOCK -> kSecAttrAccessibleAfterFirstUnlock
        KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY ->
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY ->
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
    }

/**
 * The app's `CFBundleIdentifier`, or a constant when there is none.
 *
 * A bundle without an identifier is not a real app — it happens in a test binary and in a bare
 * framework loaded by a host that has one of its own. Falling back keeps a store openable there
 * instead of crashing a test run, and the name it falls back to is still this module's namespace
 * rather than anything a consumer could collide with by accident.
 */
private fun bundleIdentifier(): String =
    NSBundle.mainBundle.bundleIdentifier ?: FALLBACK_BUNDLE_IDENTIFIER

private const val FALLBACK_BUNDLE_IDENTIFIER = "io.github.jamal_wia.kmptoolkit.storage.unbundled"
