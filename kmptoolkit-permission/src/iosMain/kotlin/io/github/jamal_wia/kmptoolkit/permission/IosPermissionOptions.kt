package io.github.jamal_wia.kmptoolkit.permission

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionProvisional
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationOptions

/**
 * How the iOS [PermissionController] asks for what it asks for.
 *
 * @property notificationOptions what a [CommonPermission.Notifications] request asks iOS for. Alert,
 *   badge and sound by default. Must not be empty.
 * @property temporaryFullAccuracyPurposeKey a key of the `NSLocationTemporaryUsageDescriptionDictionary`
 *   entry of `Info.plist`. When it is set, requesting [CommonPermission.Location] from
 *   [PermissionStatus.Limited] (the user chose approximate location) asks iOS for temporary full accuracy
 *   under that key, as Android offers the precise upgrade, so shared code has one call; see
 *   [requestTemporaryFullAccuracy]. `null` keeps the 4.x behaviour: such a request returns
 *   [PermissionStatus.Limited] at once. A key that is absent from `Info.plist` makes
 *   [createPermissionController] throw [IllegalStateException] naming it. Must not be blank.
 */
public data class IosPermissionOptions(
    public val notificationOptions: Set<IosNotificationOption> = setOf(
        IosNotificationOption.Alert,
        IosNotificationOption.Badge,
        IosNotificationOption.Sound,
    ),
    public val temporaryFullAccuracyPurposeKey: String? = null,
) {
    init {
        require(notificationOptions.isNotEmpty()) { "notificationOptions must not be empty" }
        require(temporaryFullAccuracyPurposeKey == null || temporaryFullAccuracyPurposeKey.isNotBlank()) {
            "temporaryFullAccuracyPurposeKey must not be blank"
        }
    }
}

/** One `UNAuthorizationOptions` flag a notification request can ask for. */
public enum class IosNotificationOption {

    /** Show alerts — `UNAuthorizationOptionAlert`. */
    Alert,

    /** Badge the app icon — `UNAuthorizationOptionBadge`. */
    Badge,

    /** Play sounds — `UNAuthorizationOptionSound`. */
    Sound,

    /**
     * Ask for provisional authorization — `UNAuthorizationOptionProvisional`: iOS grants it without a
     * prompt, delivers quietly to Notification Center, and the status reads [PermissionStatus.Limited].
     * A request from there leaves this option out, so that it shows the full prompt.
     */
    Provisional,

    /**
     * Critical alerts, which sound through Do Not Disturb — `UNAuthorizationOptionCriticalAlert`. Needs
     * an entitlement Apple grants on request; without it iOS ignores the option.
     */
    CriticalAlert,
}

/** The `UNAuthorizationOptions` bits for [options]. */
internal fun authorizationOptions(options: Set<IosNotificationOption>): UNAuthorizationOptions =
    options.fold(0uL) { bits: ULong, option: IosNotificationOption ->
        bits or when (option) {
            IosNotificationOption.Alert -> UNAuthorizationOptionAlert
            IosNotificationOption.Badge -> UNAuthorizationOptionBadge
            IosNotificationOption.Sound -> UNAuthorizationOptionSound
            IosNotificationOption.Provisional -> UNAuthorizationOptionProvisional
            IosNotificationOption.CriticalAlert -> UNAuthorizationOptionCriticalAlert
        }
    }
