package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
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
}

/** Every string granted, and at least one string asked about. */
internal fun allGranted(answers: Map<String, Boolean>): Grant =
    if (answers.isNotEmpty() && answers.values.all { it }) Grant.Full else Grant.None

/** The rule for a runtime [permission] on [sdkInt], or `null` when it is not a runtime permission. */
internal fun runtimeRule(permission: Permission, sdkInt: Int): RuntimeRule? = when (permission) {
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
