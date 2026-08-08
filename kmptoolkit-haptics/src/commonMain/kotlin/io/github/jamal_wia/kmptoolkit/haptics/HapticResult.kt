package io.github.jamal_wia.kmptoolkit.haptics

/**
 * What became of a [HapticFeedback.perform] call.
 *
 * Haptics are an enhancement, never a requirement: a device with no vibration motor, or an app
 * whose manifest is missing `android.permission.VIBRATE`, must not crash because of a decorative
 * tap. So [HapticFeedback.perform] never throws — it returns one of these instead, and a caller
 * that does not care can ignore the value entirely.
 *
 * The value is typed rather than a message string on purpose: presenting anything to a user (for
 * instance, hiding a "vibration" preference on hardware that cannot vibrate) is the app's job, and
 * the app owns its own wording. See `docs/01-architecture.md`.
 */
public enum class HapticResult {

    /**
     * The platform accepted the request.
     *
     * This is a statement about the *request*, not about the motor: the OS may still suppress the
     * vibration because the user turned haptics off system-wide, because the device is in a
     * do-not-disturb state, or because it is a simulator. Neither platform reports that back, so
     * neither does this module. On iOS the request is additionally queued to the main thread, so
     * `PERFORMED` means "handed to UIKit", not "already felt".
     */
    PERFORMED,

    /**
     * The device cannot produce haptics at all — on Android, no vibrator service or
     * `Vibrator.hasVibrator() == false`.
     *
     * This is a property of the hardware, not of a single call: if one [HapticType] returns
     * `UNAVAILABLE`, all of them will, for as long as this instance lives. It is also what
     * [noOpHapticFeedback] reports, so a wrapper that suppresses haptics can answer the same way.
     */
    UNAVAILABLE,

    /**
     * The app is not allowed to vibrate — Android only, and it means the consuming app did not
     * declare `android.permission.VIBRATE` in its manifest.
     *
     * This module deliberately declares no permission of its own (see
     * `docs/kmptoolkit-haptics/05-platform-notes.md`), so an app that never declares it gets this
     * result on every call rather than a `SecurityException` from somewhere deep in a UI handler.
     * It is a build-configuration mistake, not a runtime condition to recover from: declare the
     * permission. It is a normal, install-time permission — there is no runtime prompt to show.
     */
    PERMISSION_DENIED,

    /**
     * The platform rejected the request for a reason that is neither a missing motor nor a missing
     * permission.
     *
     * In practice this is Android's vibrator service refusing or being unreachable: a rejected
     * effect, or a `RuntimeException` wrapping a dead binder when the system service restarts. It
     * is transient or device-specific rather than a standing property of the install, so — unlike
     * [UNAVAILABLE] — it says nothing about whether the next call will work.
     *
     * It exists so that `perform` can keep its promise never to throw: a decorative tap must not
     * propagate a `RuntimeException` into the caller that asked for it.
     */
    FAILED,
}
