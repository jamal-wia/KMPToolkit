package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.health.connect.HealthPermissions
import android.os.Build

/** How much of a permission a set of Android grants amounts to. */
internal enum class Grant { Full, Limited, None }

/**
 * What a runtime [Permission] is on one API level: the dialog that requests it, or the reason there is
 * none. Pure data — the controller turns it into a status — so every API-level boundary is testable
 * by passing an `sdkInt`, without a second Robolectric image.
 */
internal sealed interface RuntimeRule {

    /**
     * Requested as [androidPermissions] in one system dialog; [grantOf] folds one grant state per string
     * into how much of the permission that is.
     */
    class Dialog(
        val androidPermissions: List<String>,
        val grantOf: (Map<String, Boolean>) -> Grant = ::allGranted,
    ) : RuntimeRule

    /** There is no runtime grant to obtain on this API level: the capability is simply there. */
    data object NoGrantNeeded : RuntimeRule

    /**
     * This entry *is* [permission] on this API level, in every respect — status, dialog and remembered
     * refusals. Sharing the entry, not just its Android strings, is what keeps the two from disagreeing:
     * a refusal recorded under one key and read under another would read as never refused.
     */
    class SameAs(val permission: Permission) : RuntimeRule

    /**
     * Not a permission before API 33: whether the user has the app's notifications switched on, which
     * only settings changes.
     */
    data object NotificationSwitch : RuntimeRule

    /**
     * A permission no app can prompt for — install-time, signature, privileged, development: granted
     * when the app holds [androidPermission], unavailable otherwise, and never a dialog.
     */
    class StatusOnly(val androidPermission: String) : RuntimeRule
}

/** A permission's protection level on this device, as far as this library cares. */
internal enum class Protection { Dangerous, Other }

/**
 * Background entries Android grants only on top of a foreground one: a request for the key while its
 * value is not usable has no dialog to show, and the two never share one.
 */
internal fun foregroundOf(permission: Permission): Permission? = when (permission) {
    CommonPermission.LocationBackground -> CommonPermission.Location
    AndroidPermission.BodySensorsBackground -> AndroidPermission.BodySensors
    else -> null
}

/** Every string granted, and at least one string asked about. */
internal fun allGranted(answers: Map<String, Boolean>): Grant =
    if (answers.isNotEmpty() && answers.values.all { it }) Grant.Full else Grant.None

/**
 * The rule for a runtime [permission] on [sdkInt], for an app targeting [targetSdk], or `null` when it
 * is not a runtime permission at all. [protectionOf] reads a raw permission's protection level, `null`
 * when this device does not know the permission.
 */
internal fun runtimeRule(
    permission: Permission,
    sdkInt: Int,
    targetSdk: Int,
    protectionOf: (String) -> Protection?,
): RuntimeRule? = when (permission) {
    CommonPermission.Notifications ->
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeRule.Dialog(listOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            RuntimeRule.NotificationSwitch
        }

    CommonPermission.Microphone -> RuntimeRule.Dialog(listOf(Manifest.permission.RECORD_AUDIO))
    CommonPermission.Camera -> RuntimeRule.Dialog(listOf(Manifest.permission.CAMERA))
    // Both, in one dialog: only then does Android 12+ render the Precise / Approximate choice.
    CommonPermission.Location -> RuntimeRule.Dialog(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        grantOf = ::locationGrant,
    )

    CommonPermission.LocationBackground ->
        // Before API 29 there is no separate background grant: foreground location covers it.
        if (sdkInt >= Build.VERSION_CODES.Q) {
            RuntimeRule.Dialog(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            RuntimeRule.SameAs(CommonPermission.Location)
        }

    CommonPermission.AudioLibrary -> RuntimeRule.Dialog(
        listOf(
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            },
        ),
    )

    CommonPermission.BluetoothConnect ->
        if (sdkInt >= Build.VERSION_CODES.S) {
            RuntimeRule.Dialog(listOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            // The install-time BLUETOOTH permission covers it.
            RuntimeRule.NoGrantNeeded
        }

    CommonPermission.BluetoothScan ->
        if (sdkInt >= Build.VERSION_CODES.S) {
            RuntimeRule.Dialog(listOf(Manifest.permission.BLUETOOTH_SCAN))
        } else {
            // Scan results need location below API 31, and nothing else.
            RuntimeRule.SameAs(CommonPermission.Location)
        }

    CommonPermission.BluetoothAdvertise ->
        if (sdkInt >= Build.VERSION_CODES.S) {
            RuntimeRule.Dialog(listOf(Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            RuntimeRule.NoGrantNeeded
        }

    CommonPermission.PhotoLibrary -> when {
        // Partial access: the dialog offers "Select photos and videos" only when this is asked too.
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> RuntimeRule.Dialog(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            grantOf = ::photoGrant,
        )

        sdkInt >= Build.VERSION_CODES.TIRAMISU -> RuntimeRule.Dialog(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
        )

        else -> RuntimeRule.Dialog(listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    CommonPermission.PhotoLibraryAdd ->
        if (sdkInt >= Build.VERSION_CODES.Q) {
            // Scoped storage: an app adds media through MediaStore without any permission.
            RuntimeRule.NoGrantNeeded
        } else {
            RuntimeRule.Dialog(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }

    CommonPermission.Contacts ->
        RuntimeRule.Dialog(listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))

    CommonPermission.Calendar ->
        RuntimeRule.Dialog(listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))

    CommonPermission.CalendarWriteOnly -> RuntimeRule.Dialog(listOf(Manifest.permission.WRITE_CALENDAR))

    CommonPermission.MotionActivity ->
        if (sdkInt >= Build.VERSION_CODES.Q) {
            RuntimeRule.Dialog(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        } else {
            // An install-time permission before API 29.
            RuntimeRule.NoGrantNeeded
        }

    AndroidPermission.NearbyWifiDevices ->
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeRule.Dialog(listOf(Manifest.permission.NEARBY_WIFI_DEVICES))
        } else {
            // Wi-Fi scan results need location below API 33.
            RuntimeRule.SameAs(CommonPermission.Location)
        }

    AndroidPermission.LocalNetwork ->
        // A runtime permission only for an app that opted into API 37 behaviour, on API 37.
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN && targetSdk >= Build.VERSION_CODES.CINNAMON_BUN) {
            RuntimeRule.Dialog(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        } else {
            RuntimeRule.NoGrantNeeded
        }

    AndroidPermission.BodySensors ->
        if (sdkInt >= Build.VERSION_CODES.BAKLAVA) {
            RuntimeRule.Dialog(listOf(HealthPermissions.READ_HEART_RATE))
        } else {
            RuntimeRule.Dialog(listOf(Manifest.permission.BODY_SENSORS))
        }

    AndroidPermission.BodySensorsBackground -> when {
        sdkInt >= Build.VERSION_CODES.BAKLAVA -> RuntimeRule.Dialog(listOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND))
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> RuntimeRule.Dialog(listOf(Manifest.permission.BODY_SENSORS_BACKGROUND))
        // No separate background grant before API 33: the foreground one covers it.
        else -> RuntimeRule.SameAs(AndroidPermission.BodySensors)
    }

    is AndroidPermission.Runtime -> when (protectionOf(permission.name)) {
        Protection.Dangerous -> RuntimeRule.Dialog(listOf(permission.name))
        Protection.Other -> RuntimeRule.StatusOnly(permission.name)
        null -> null
    }

    else -> null
}

/**
 * Photos and videos from API 34: every declared full-access string is the full grant; the
 * user-selected string alone — the user picked some items — is a limited one.
 */
private fun photoGrant(answers: Map<String, Boolean>): Grant {
    val full: Map<String, Boolean> = answers.filterKeys { it != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
    return when {
        full.isNotEmpty() && full.values.all { it } -> Grant.Full
        answers[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true -> Grant.Limited
        else -> Grant.None
    }
}

/**
 * Location: precise is the full grant, an approximate-only grant — which the user chose in the dialog,
 * and which yields a usable location — is a limited one.
 */
private fun locationGrant(answers: Map<String, Boolean>): Grant = when {
    answers[Manifest.permission.ACCESS_FINE_LOCATION] == true -> Grant.Full
    answers[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> Grant.Limited
    else -> Grant.None
}
