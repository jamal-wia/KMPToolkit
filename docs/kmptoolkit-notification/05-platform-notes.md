# kmptoolkit-notification — Platform notes

What each platform actually does, including where it does less than the shared API might suggest.

## Channels and categories — the divergence, in full

This is the one place where a cross-platform notification API is most tempted to lie, so it is worth
being precise.

**Android has notification channels.** Since API 26 every notification must name one, the app
creates it once, and from that moment it belongs to the **user**: they see it as a row in system
settings with the `name` and `description` you supplied, they can mute it, change its importance,
change its sound, and the app cannot undo any of that. An app can create and delete channels; it
cannot raise the importance of a channel that already exists, and re-creating a deleted channel
restores the user's settings rather than the app's.

**iOS has nothing like that.** There is no per-app grouping the user can configure, no per-category
sound, no per-category importance. Notification settings on iOS are a single app-level switch (plus
Focus rules).

**iOS categories are not channels.** A `UNNotificationCategory` exists for one purpose: to declare
the **action buttons** a notification may show, plus a few presentation options. It has no name the
user sees, carries no sound, no importance, and cannot be muted independently. Mapping
`NotificationChannelSpec` onto a category would be a mapping between two things that share nothing
but the word "category" — so this module does not. Categories are exposed for what they are, through
`LocalNotification.iosCategoryId`, and you register them yourself (see
[`03-guide.md`](03-guide.md#action-buttons)).

### What the common API actually promises

`NotificationChannelSpec` promises *"where this notification belongs and how loud it may be"*, and
each platform keeps as much of that promise as it can:

| Field | Android | iOS |
|---|---|---|
| `id` | the channel id; the channel is created on first post | `threadIdentifier`, which groups the app's notifications together in Notification Centre — a real, honest use of the same "which stream is this" idea |
| `name` | shown in system settings | **unused.** iOS shows the user no per-channel row to name |
| `description` | shown in system settings | **unused**, same reason |
| `importance` | channel importance, fixed at creation | `interruptionLevel`, applied **per notification**: `Low` → `passive`, `Default` and `High` → `active` (iOS 15+; ignored below) |
| `sound` | channel sound, fixed at creation | the notification's own sound, applied per notification |

The mechanics differ — Android freezes importance and sound at channel creation, iOS applies them
every time — and that difference is visible: on Android, shipping a changed `importance` for an
existing channel does nothing, while on iOS the very next notification uses the new value. That is
the platforms' behaviour, not a bug in the module, and it is why
[`03-guide.md`](03-guide.md#channels) says to decide your channel split up front.

`NotificationImportance.High` maps to `active`, not `timeSensitive`. `timeSensitive` is what breaks
through a Focus mode, and it requires the `com.apple.developer.usernotifications.time-sensitive`
entitlement. A library that silently required an entitlement would change how your app is reviewed;
if you need it, set the interruption level yourself in platform code.

## Permissions and the manifest

**This module declares no Android permission**, and a `LibraryManifestTest` asserts it against a real
`PackageManager` on every build (see [`docs/01-architecture.md`](../01-architecture.md#asserting-the-no-permissions-invariant)).

**No dependency merges one either.** `androidx.core`, the only Android dependency, contributes no
`<uses-permission>`. The only entries the assertion has to subtract come from the *test* harness and
never reach a published artifact:

| Entry | Source |
|---|---|
| `android.permission.REORDER_TASKS` | `androidx.test`'s own manifest |
| `<testPackage>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | synthesised by AGP for the test application |

If a future dependency upgrade adds a third, that test fails rather than the permission arriving
silently in someone's app — which is the point of pinning the exact set.

Your app declares:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

and, only if you use action buttons, a receiver:

```xml
<receiver android:name=".NotificationActionReceiver" android:exported="false">
    <intent-filter>
        <action android:name="${applicationId}.KMPTOOLKIT_NOTIFICATION_ACTION" />
    </intent-filter>
</receiver>
```

Nothing is required in `Info.plist` on iOS: notification authorization is a runtime request with no
usage-description string.

## Android specifics

- **`POST_NOTIFICATIONS` exists from API 33.** Below that there is no runtime grant, so
  `PermissionDenied` cannot occur — but notifications can still be off app-wide, which is
  `NotificationsDisabled`. The two are genuinely different states and both are reported.
- **Channels exist from API 26.** Below that the module skips channel creation and
  `ChannelBlocked` cannot occur; the sound is applied to the notification itself, since there is no
  channel to carry one. All three of `Silent`, `Default` and `Custom` are applied there — a
  `NotificationCompat` notification is silent unless a sound is set explicitly, so `Default` has to
  be spelled out as the platform's default notification URI or it would quietly mean `Silent`. A
  `NotificationImportance.Low` notification is kept silent below 26 too, matching what
  `IMPORTANCE_LOW` does from 26 onwards. `minSdk` for the suite is 24, so this path is real, and
  `AndroidNotifierLegacyTest` runs at `sdk = 24` to keep it that way.
- **The small icon is mandatory**, drawn as a silhouette from its alpha channel on API 21+. A
  resource id that does not resolve is caught before posting and returned as `Failed`.
- **`setOnlyAlertOnce(true)` is always applied**, so re-posting an id to update it does not buzz
  again. That is what makes a progress notification bearable.
- **Ids are hashed** from your string to a non-negative, non-zero `Int` — non-zero because
  `startForeground` rejects 0, and stable across processes because `String.hashCode` is specified.
  Two different strings could in principle collide; use readable, distinct ids.
- **A channel is "blocked" two ways**: muted directly (`IMPORTANCE_NONE`), or — from API 28 — sitting
  in a `NotificationChannelGroup` the user muted, which silences every channel in it while each one
  still reports its own original importance. Both come back as `ChannelBlocked`. This module creates
  no groups, but a consumer's channel may belong to one created elsewhere in their app.
- **The channel write is skipped for a redundant progress frame**, never the channel *read*: a frame
  about to be coalesced does not need `createNotificationChannel` re-issued, but a channel that is
  missing is still created, because the platform drops a notification on a channel that does not
  exist without a word.
- **`cancelAll` clears the app's whole tray**, including notifications posted by a previous process
  or by another part of your app.

## iOS specifics

- **Authorization is one state.** iOS does not distinguish "the user denied the prompt" from
  "notifications are off in Settings", so `NotificationsDisabled` is never returned there;
  `PermissionDenied` covers both.
- **`add(request:)` is asynchronous** and its `NSError` is awaited, so a rejection becomes
  `Failed(cause)` rather than a success you would have believed.
- **No progress bar, no small icon, no `ongoing`, no `autoCancel`.** Those fields are ignored; put a
  percentage in `body` if it must be visible.
- **`cancelAll` removes delivered notifications only.** Pending (scheduled) requests are left alone,
  mirroring Android's `cancelAll`, which does not cancel alarms either. Scheduling is
  [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md)'s domain, and it owns cancelling
  what it scheduled.
- **`interruptionLevel` is guarded by a runtime OS-version check**, because the setter arrived in
  iOS 15 and this module's deployment target is older.

## Both platforms

- Nothing is thrown from `post`, `cancel` or `cancelAll` for a runtime condition — a missing
  permission, a dead system service, a payload the framework refuses. The one exception is a blank
  `id` passed to `post`, which is a programming error and throws `IllegalArgumentException`; see
  [`04-api-reference.md`](04-api-reference.md#notifier). On iOS that check is what stands between a
  blank id and process death, because `UNNotificationRequest` rejects it with an Objective-C
  exception Kotlin/Native cannot catch.
- Nothing is persisted. The module has no database, no list of what it has posted, and nothing to
  restore after a reboot or a process death — beyond in-memory progress-coalescing state, which is
  meant to be lost.
- A notification that was accepted may still not be drawn: Do Not Disturb, a Focus mode, a paired
  watch. Neither platform reports that back, so neither does this module.
