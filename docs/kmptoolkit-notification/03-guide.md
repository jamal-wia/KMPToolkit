# kmptoolkit-notification — Guide

Scenarios in the order you are likely to meet them, and the mistakes each one invites.

## Ids: one string, three jobs

`post(id, ...)` uses your id as the replace key, the cancel key, and (on Android) the seed for the
platform's integer id. Two rules follow:

- **Re-post the same id to update.** A progress notification is one id posted many times, not many
  notifications.
- **Use a different id per concurrently visible thing.** Two downloads showing at once are two ids.

The id is never shown to the user and never leaves the device.

## Channels

A channel is created on the first post that mentions it, and it is the **user's** afterwards. That
is a platform rule, not a library choice, and it has one consequence worth internalising:

> Changing `importance` or `sound` on a `NotificationChannelSpec` you have already shipped does
> nothing on Android. Deleting the channel and re-creating it under the same id does nothing either
> — the platform restores the user's settings.

So decide the split up front, and split by *how the user would want to control it*: "Downloads"
(quiet) and "Messages" (alerting) are two channels, because a user who mutes one probably still
wants the other. If you genuinely must change a channel's behaviour later, ship a **new id** and
accept that the old one lingers in settings until the user clears data.

`name` and `description` *are* updated on an existing channel, so localising them later works.

Both are required parameters. This library has no copy of its own — see
[`docs/01-architecture.md`](../01-architecture.md).

## Progress notifications

```kotlin
suspend fun onBytes(downloaded: Long, total: Long) {
    notifier.post(
        id = "download",
        notification = LocalNotification(
            title = strings.downloading,
            body = strings.percent(percent),
            channel = downloadsChannel,          // NotificationImportance.Low
            progress = NotificationProgress.Determinate(percent),
            ongoing = true,                      // not swipeable while it runs
        ),
    )
}

suspend fun onFinished() {
    notifier.post(
        id = "download",                          // same id: replaces the progress notification
        notification = LocalNotification(
            title = strings.downloadComplete,
            body = strings.fileName,
            channel = downloadsChannel,
            progress = null,                      // no bar any more
            ongoing = false,
            contentExtras = mapOf("screen" to "downloads"),
        ),
    )
}
```

Call `post` as often as you like. The module suppresses a determinate update that would not move the
bar, returning `NotificationResult.Coalesced`, under two limits configured on `NotificationConfig`:

1. **Bucket** (`progressBucketPercent`, default 10) — the update is in the same tenth as the last
   one actually posted.
2. **Rate** (`minProgressInterval`, default 500 ms) — less than that has elapsed since the last post
   for this id.

Two updates always get through, whatever the limits say:

- anything that is not `Determinate` — `null` or `Indeterminate`, which is what a terminal frame
  looks like;
- a `Determinate(100)`.

Both then reset the id's state, so the next run starts fresh. That is what makes "post the completed
frame with `progress = null`" safe rather than a rule you have to remember — but posting the final
frame *with* a percentage is safe too, because 100 is never suppressed.

**Do not build your own throttle on top.** A caller that only posts every 5% just gets a coarser bar;
the module is already bounding the rate.

**iOS has no progress bar.** `progress` there only drives coalescing. If the percentage must be
visible on iOS, put it in `body` — as the example does.

## The tap target

`contentExtras = null` (the default) means the notification does nothing when tapped. That is right
for a progress notification.

Any non-null map — including an empty one — makes it open your app:

- **Android**: the launcher activity is launched with each entry as a string extra, with
  `CLEAR_TOP | SINGLE_TOP` so an already-running activity gets `onNewIntent` instead of being
  recreated. Read the extras there and route.
- **iOS**: ignored. A tap goes to your `UNUserNotificationCenterDelegate`; routing there is Swift
  code that reads `userInfo`, which this module does not set. This is the one field where the two
  platforms genuinely do not meet, and pretending otherwise would mean the library owning your app
  delegate.

## Action buttons

Buttons need something that survives your process being killed, and it is different on each
platform. The library builds the part it can and names the part it cannot.

**Android.** Declare a manifest receiver for the broadcast the module fires:

```xml
<receiver android:name=".NotificationActionReceiver" android:exported="false">
    <intent-filter>
        <action android:name="${applicationId}.KMPTOOLKIT_NOTIFICATION_ACTION" />
    </intent-filter>
</receiver>
```

```kotlin
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId: String = NotificationActionIntent.actionId(intent) ?: return
        val notificationId: String? = NotificationActionIntent.notificationId(intent)
        when (actionId) {
            "cancel" -> DownloadService.cancel(context, notificationId)
        }
    }
}
```

The action string is derived from **your** application id, so two apps built on this library never
receive each other's taps. Override it with `NotificationConfig(actionBroadcastAction = ...)` if you
already have a receiver of your own; `NotificationActionIntent.defaultAction(context)` returns the
derived value if you want to assert in a test that your manifest and the notifier agree.

It must be a *manifest* receiver. A receiver registered in code, or a callback held by the library,
is gone exactly when a notification button is most likely to be tapped.

**iOS.** Register the category once, at app start, with your own action identifiers:

```swift
let cancel = UNNotificationAction(identifier: "cancel", title: cancelTitle, options: [])
let category = UNNotificationCategory(identifier: "download", actions: [cancel], intentIdentifiers: [])
UNUserNotificationCenter.current().setNotificationCategories([category])
```

and name it on the notification:

```kotlin
LocalNotification(
    /* ... */
    actions = listOf(NotificationAction(id = "cancel", label = strings.cancel)),  // Android
    iosCategoryId = "download",                                                   // iOS
)
```

The library never calls `setNotificationCategories`, because that call replaces your app's *entire*
category set — a library doing it would silently delete categories registered by your own code or
another SDK.

Give the category's action identifiers the same values as your `NotificationAction.id`s, and the
code that handles a tap converges on one `when` on both platforms.

## Deciding what to do about a result

| Result | What happened | A reasonable response |
|---|---|---|
| `Posted` | handed to the platform | nothing |
| `Coalesced` | suppressed as a redundant progress update | nothing |
| `PermissionDenied` | no runtime grant (Android 13+ / iOS) | prompt, at a moment the user expects |
| `NotificationsDisabled` | app-level toggle is off (Android) | offer a link to system settings |
| `ChannelBlocked` | that channel is muted (Android 8+) | offer a link to that channel's settings |
| `Failed` | icon that does not resolve, framework refusal | log it; it is a bug, not a user state |

Nothing here throws, and nothing here is a string to show a user. Both are deliberate.

## Common mistakes

- **Treating `Coalesced` as a failure.** It means the previous frame is still correct.
- **Posting the completed frame under a new id.** The progress notification stays on screen forever
  next to it. Same id, always.
- **A channel per notification.** Channels are user-facing settings rows, not tags. A handful per
  app is the intended scale.
- **Expecting `ongoing = true` to survive on iOS.** It does not exist there; the notification is
  dismissible.
- **Building the notification's copy inside shared code from a hardcoded string.** The module takes
  already-localized text precisely so your existing localization pipeline stays in charge.
- **Assuming a full-colour icon renders.** Android draws the small icon as a silhouette from its
  alpha channel; a photo becomes a white square.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform does with a channel, and the manifest
- [`06-testing.md`](06-testing.md) — asserting all of the above without a device
