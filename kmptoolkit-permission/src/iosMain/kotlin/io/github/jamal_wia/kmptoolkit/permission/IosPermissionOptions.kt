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
 */
public data class IosPermissionOptions(
    public val notificationOptions: Set<IosNotificationOption> = setOf(
        IosNotificationOption.Alert,
        IosNotificationOption.Badge,
        IosNotificationOption.Sound,
    ),
) {
    init {
        require(notificationOptions.isNotEmpty()) { "notificationOptions must not be empty" }
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
