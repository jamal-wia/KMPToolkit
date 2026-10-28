package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi

/**
 * The authorizations only iOS has. None of these is visible to shared code: shared code that needs
 * one takes a [Permission] value from iOS code, exactly as for an `AndroidPermission` — see
 * [Permission].
 *
 * Each needs its own `Info.plist` purpose string, named on the entry. Without it the entry reads
 * [PermissionStatus.Unavailable] and a request shows nothing, instead of iOS terminating the app.
 *
 * **The set can grow in a minor release**, like [CommonPermission]'s: add an `else` branch to an
 * exhaustive `when` where you do not care about every entry.
 */
@OptIn(ToolkitInternalApi::class)
public sealed interface IosPermission : Permission {

    /**
     * App Tracking Transparency — `ATTrackingManager`, purpose string `NSUserTrackingUsageDescription`.
     *
     * iOS shows the prompt only while the app is active; a request made earlier — during launch, say —
     * returns [PermissionStatus.NotDetermined] at once. With "Allow Apps to Request to Track" off in
     * Settings, iOS reports a refusal without ever prompting: [PermissionStatus.PermanentlyDenied].
     */
    public data object Tracking : IosPermission {
        override val id: String = "TRACKING"
    }

    /**
     * Speech recognition — `SFSpeechRecognizer`, purpose string `NSSpeechRecognitionUsageDescription`.
     * Recognising live speech also needs [CommonPermission.Microphone].
     */
    public data object SpeechRecognition : IosPermission {
        override val id: String = "SPEECH_RECOGNITION"
    }

    /**
     * Full access to the user's reminders — EventKit. Purpose string
     * `NSRemindersFullAccessUsageDescription` from iOS 17, `NSRemindersUsageDescription` below it.
     * Android has no system reminders store, which is why this is not a [CommonPermission].
     */
    public data object Reminders : IosPermission {
        override val id: String = "REMINDERS"
    }

    /**
     * Whether the user has a Focus on, shared with this app — `INFocusStatusCenter`, purpose string
     * `NSFocusStatusUsageDescription`. The app also needs the Communication Notifications capability.
     */
    public data object FocusStatus : IosPermission {
        override val id: String = "FOCUS_STATUS"
    }

    public companion object {

        /** Every iOS-only permission, in declaration order. */
        public val entries: List<IosPermission> by lazy { listOf(Tracking, SpeechRecognition, Reminders, FocusStatus) }
    }
}
