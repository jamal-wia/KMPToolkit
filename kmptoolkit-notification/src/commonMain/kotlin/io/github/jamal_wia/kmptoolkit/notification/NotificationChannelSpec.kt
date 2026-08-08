package io.github.jamal_wia.kmptoolkit.notification

/**
 * Where a notification is posted: an Android notification channel, and as much of that idea as iOS
 * genuinely has.
 *
 * **The two platforms disagree here, and this type does not pretend otherwise.** On Android a
 * channel is mandatory from API 26, is created by the app once and owned by the *user* afterwards,
 * and is what the user sees and toggles in system settings. iOS has no such thing. So each field
 * says exactly what each platform does with it:
 *
 * | Field | Android | iOS |
 * |---|---|---|
 * | [id] | the channel id; created on first post | `threadIdentifier` — groups the app's notifications in Notification Centre |
 * | [name] | shown in system settings | not used: iOS shows the user no per-channel entry to name |
 * | [description] | shown in system settings | not used, same reason |
 * | [importance] | channel importance, fixed at creation | `interruptionLevel`, per notification |
 * | [sound] | channel sound, fixed at creation | the notification's sound, per notification |
 *
 * The full reasoning — and why the alternative (an iOS `UNNotificationCategory`) is *not* what a
 * channel maps to — is in `docs/kmptoolkit-notification/05-platform-notes.md`.
 *
 * [name] and [description] have no defaults on purpose. They are shown to a user, this library has
 * no copy to offer, and an English placeholder shipped by a library is exactly the kind of string
 * that reaches production — the same rule as `kmptoolkit-biometric`'s prompt text.
 *
 * **On Android, [importance] and [sound] apply only when the channel is first created.** Re-posting
 * with a changed spec does not change an existing channel: the platform hands those settings to the
 * user at creation and refuses later edits by the app. "Two different sounds" is therefore two
 * channels, not one channel with a flag — and if you genuinely must change one, you must delete and
 * re-create the channel under a **new id**, because a re-created id restores the user's old
 * settings.
 *
 * @property id stable identifier; also the grouping key on both platforms. Must not be blank.
 * @property name already-localized channel name, shown to the user on Android. Must not be blank.
 * @property description already-localized channel description, shown to the user on Android.
 * @property importance how loudly notifications on this channel may announce themselves.
 * @property sound what they play.
 * @throws IllegalArgumentException if [id] or [name] is blank. A blank channel name renders as an
 *   empty row in system settings, which is a bug worth failing at construction.
 */
public data class NotificationChannelSpec(
    public val id: String,
    public val name: String,
    public val description: String = "",
    public val importance: NotificationImportance = NotificationImportance.Default,
    public val sound: NotificationSound = NotificationSound.Default,
) {
    init {
        require(id.isNotBlank()) { "Channel id must not be blank." }
        require(name.isNotBlank()) { "Channel name must not be blank; it is shown to the user." }
    }
}

/**
 * How much attention notifications on a channel may demand.
 *
 * Three levels, because three is what both platforms can express without invention. Android has
 * five (`NONE` and `MIN` are omitted: `NONE` is the user's decision, not the app's, and `MIN` is
 * indistinguishable from `LOW` on modern versions); iOS has four interruption levels, of which the
 * fourth — `critical` — needs an Apple-granted entitlement no library may assume.
 */
public enum class NotificationImportance {

    /**
     * Quiet: no sound, no heads-up banner. The right level for progress and ongoing work.
     *
     * Android `IMPORTANCE_LOW`; iOS `UNNotificationInterruptionLevelPassive`, which also keeps the
     * notification out of a locked screen's spotlight.
     */
    Low,

    /**
     * Ordinary: makes a sound, may or may not peek.
     *
     * Android `IMPORTANCE_DEFAULT`; iOS `UNNotificationInterruptionLevelActive`.
     */
    Default,

    /**
     * Urgent: peeks as a heads-up banner and makes a sound.
     *
     * Android `IMPORTANCE_HIGH`. **On iOS this behaves the same as [Default]** — the level that
     * would break through a Focus mode is `timeSensitive`, which requires the
     * `com.apple.developer.usernotifications.time-sensitive` entitlement; requesting it on your
     * behalf would silently change how your app is reviewed, so this module does not.
     */
    High,
}
