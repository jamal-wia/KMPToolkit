package io.github.jamal_wia.kmptoolkit.storage

/**
 * The Keychain attributes of one iOS secure store, validated when the object is built.
 *
 * Passed to [createSecureKeyValueStorage]. A plain class rather than a `data class`, so a field can be
 * added in a later version without changing `copy` or `componentN` for code compiled against this one.
 *
 * ```kotlin
 * val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(
 *     options = IosSecureStorageOptions(accessibility = KeychainAccessibility.WHEN_UNLOCKED),
 * )
 * ```
 *
 * An invalid combination throws [IllegalArgumentException] here, at construction, never from a store
 * operation: the factory and the stores never fail on their own configuration at run time.
 *
 * @param accessibility `kSecAttrAccessible`: when iOS may decrypt the items. Applied on **every** write,
 *   an update of an existing item included, so an item takes a changed value on its next `put`. A key that
 *   is never written again keeps the class it has; call [KeyValueStorage.clear] to move everything.
 * @param accessGroup `kSecAttrAccessGroup`: the group the items are filed under, for sharing them with an
 *   app extension or a sibling app. Needs the Keychain Sharing entitlement; a group your entitlements do
 *   not grant makes every operation fail with [StorageError.Unsupported] ([UnsupportedReason.MISSING_ENTITLEMENT]).
 *   `null` uses the app's default access group, and [KeyValueStorage.clear] then touches only that group.
 *   Must not be blank. **Known limit:** only `clear()` is pinned to the default group. With `null`, `get`, `put` and
 *   `remove` send a query that names no group, which the Keychain matches against every group the app can reach, so a
 *   same-named store in a shared group can be read, deleted or overwritten by a `null`-group store. This is
 *   deliberate: pinning reads and writes to a resolved default group would hide items created under an earlier
 *   default group (the order of `keychain-access-groups` changed), which is a data loss. When several stores share
 *   a keychain group, give each an explicit `accessGroup` or a distinct store name.
 * @param synchronizable `kSecAttrSynchronizable`: puts the items in iCloud Keychain, so they leave the
 *   device. Reads and writes pass the exact value, so a store never sees the other kind of item.
 *   Rejected with an `accessibility` whose name ends in `THIS_DEVICE_ONLY` (iOS cannot sync such an
 *   item), which includes the default, so pass a different `accessibility` along with `true`; also
 *   rejected with [userPresence].
 * @param userPresence a `SecAccessControl` gate on reading the items; `null` for none. iOS keeps one item
 *   per key, so the system sheet shows on **every** `get` (and may show on a `put` that replaces an existing
 *   item); cancelling it answers [StorageError.AccessDenied]. See [IosUserPresence].
 */
public class IosSecureStorageOptions(
    public val accessibility: KeychainAccessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
    public val accessGroup: String? = null,
    public val synchronizable: Boolean = false,
    public val userPresence: IosUserPresence? = null,
) {

    init {
        require(accessGroup == null || accessGroup.isNotBlank()) {
            "accessGroup must be null (the app's default group) or a non-blank access group identifier"
        }
        require(!synchronizable || !accessibility.isThisDeviceOnly) {
            "synchronizable = true cannot be combined with accessibility = $accessibility: iOS does not sync a " +
                "THIS_DEVICE_ONLY item. Pass WHEN_UNLOCKED or AFTER_FIRST_UNLOCK."
        }
        require(!synchronizable || userPresence == null) {
            "synchronizable = true cannot be combined with userPresence: a user-presence item is never synced"
        }
    }
}

/**
 * A user-presence requirement for reading a store's items, applied through a `SecAccessControl`.
 *
 * Because iOS keeps **one Keychain item per key**, the system authentication sheet shows on **every**
 * `get`, once per read, not once per session and not once per store. Keep a read value in memory in
 * your app instead of reading it again (an in-memory cache is the usual answer). A `put` that replaces an
 * existing item may show the sheet as well.
 *
 * The sheet is dismissed when the coroutine that waits for it is cancelled. The user cancelling it answers
 * [StorageError.AccessDenied] with the platform code `-128`; a failed authentication answers
 * [StorageError.AccessDenied] with `-25293`. Neither deletes anything: retry only on a user action.
 *
 * @param policy which authentication satisfies the requirement.
 * @param prompt the reason shown in the system sheet. App-supplied and app-localized; the library has no
 *   text of its own. Must not be blank.
 */
public class IosUserPresence(
    public val policy: IosUserPresencePolicy,
    public val prompt: String,
) {

    init {
        require(prompt.isNotBlank()) { "prompt must not be blank: it is the reason the system sheet shows" }
    }
}

/** The `SecAccessControl` flag an [IosUserPresence] asks for. */
public enum class IosUserPresencePolicy {

    /** `.userPresence`: biometry or the device passcode. */
    USER_PRESENCE,

    /** `.biometryAny`: any enrolled biometry; the items survive a change of the enrolled fingers or face. */
    BIOMETRY_ANY,

    /**
     * `.biometryCurrentSet`: the biometry enrolled now. Enrolling or removing a finger or face makes the
     * items unreadable: reads then answer [StorageError.Undecryptable], and the way out is
     * [KeyValueStorage.remove] or [KeyValueStorage.clear], then writing the values again.
     */
    BIOMETRY_CURRENT_SET,
}

/** True for the `kSecAttrAccessible` classes that never leave the device (backups, device transfer, iCloud). */
internal val KeychainAccessibility.isThisDeviceOnly: Boolean
    get() = when (this) {
        KeychainAccessibility.WHEN_UNLOCKED,
        KeychainAccessibility.AFTER_FIRST_UNLOCK,
        -> false

        KeychainAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
        KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
        -> true
    }
