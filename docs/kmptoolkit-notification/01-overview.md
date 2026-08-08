# kmptoolkit-notification — Overview

Local notifications from shared Kotlin code: `NotificationManagerCompat` on Android,
`UNUserNotificationCenter` on iOS, behind one `Notifier` whose `post` tells you what the platform
actually did with your notification.

```kotlin
class DownloadPresenter(private val notifier: Notifier, private val strings: Strings) {

    private val channel = NotificationChannelSpec(
        id = "downloads",
        name = strings.downloadsChannelName,
        description = strings.downloadsChannelDescription,
        importance = NotificationImportance.Low,
    )

    suspend fun onProgress(percent: Int) {
        notifier.post(
            id = "download",
            notification = LocalNotification(
                title = strings.downloading,
                body = "$percent%",
                channel = channel,
                progress = NotificationProgress.Determinate(percent),
                ongoing = true,
            ),
        )
    }

    suspend fun onFinished() = when (val result = notifier.post("download", completedNotification())) {
        NotificationResult.Posted, NotificationResult.Coalesced -> Unit
        NotificationResult.PermissionDenied -> askForPermissionLater()
        NotificationResult.NotificationsDisabled -> offerSettingsLink()
        is NotificationResult.ChannelBlocked -> offerSettingsLink()
        is NotificationResult.Failed -> log.error(result.cause)
    }
}
```

That call site knows nothing about channels being mandatory since API 26 and immutable after
creation, about `PendingIntent` mutability flags, about `POST_NOTIFICATIONS` only existing from
Android 13, or about `UNUserNotificationCenter` accepting a request it will then quietly discard.

## The problem it solves

Posting a notification looks like one line on each platform, and behaves like neither:

- **Failure is silent by default.** `NotificationManagerCompat.notify` returns `void`. If the user
  muted your channel, or switched notifications off for the app, or the permission was never
  granted, nothing happens and nothing is reported. On iOS `add(request:)` reports success while the
  OS drops the notification because the app is not authorized. A feature built on that silence is
  untestable and unsupportable — the first bug report is "notifications don't work" with no way to
  tell which of five causes it was. `post` returns a [`NotificationResult`](04-api-reference.md)
  naming exactly one of them.
- **The two platforms disagree about channels**, and most cross-platform wrappers paper over it.
  This one does not — see [`05-platform-notes.md`](05-platform-notes.md).
- **Progress notifications get you rate-limited.** A download that re-posts per percent (or per
  chunk) hits the platform's own throttle, and Android's response is to drop notifications, so the
  bar freezes at whatever frame survived. The module coalesces determinate progress by bucket *and*
  by elapsed time, and never swallows the final frame.
- **Action buttons need consumer-side wiring on both platforms** — a manifest `BroadcastReceiver`
  on Android, a registered `UNNotificationCategory` on iOS — and a library cannot supply either.
  Rather than half-support them, the module models that seam explicitly; see
  [`03-guide.md`](03-guide.md#action-buttons).

Dependencies: [`kmptoolkit-permission`](../kmptoolkit-permission/01-overview.md) (to check
`Permission.NOTIFICATIONS`, never to request it), `kotlinx-coroutines-core`, and `androidx.core` on
Android. No DI framework, no Compose, no push SDK.

## What this is **not**

- **Not push, and not FCM/APNs.** This module shows notifications your app decides to show, on the
  device, offline. It does not receive remote messages, does not manage a device token, and has no
  `FirebaseMessagingService`. Push delivery is a different mechanism with a different failure model,
  and integrating it means platform-specific service classes and a server contract — none of which
  belongs behind a `Notifier` interface. If your push payload arrives and you then want to render it
  yourself, that part is this module's job; getting it to arrive is not.
- **Not a scheduler.** Everything posted here appears *now*. "Show this at 08:00 tomorrow" is
  [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md), which arms `AlarmManager` /
  `UNUserNotificationCenter` triggers so the OS wakes something up at a wall-clock instant. That
  module draws the same line from its side: *scheduling when something fires is its job, rendering a
  notification is this one's*. The two compose exactly as you would hope — an Android `AlarmHandler`
  fires and calls `Notifier.post`. (On iOS the scheduler hands the OS a pre-rendered notification at
  schedule time, because iOS runs none of your code at fire time.)
- **Not a permission requester.** It checks `POST_NOTIFICATIONS` and reports what it found; it never
  shows a system prompt. Deciding when to ask, and what to say first, belongs to your UI —
  `kmptoolkit-permission`'s `PermissionRequestFlow` is the piece for that.
- **Not a foreground-service helper.** It posts notifications; it does not start services, and it
  will not hand you a `Notification` object for `startForeground`. That call needs the platform type
  and an Android service lifecycle this module has no view of.
- **Not a source of user-facing text.** No default channel name, no default title, no English
  string of any kind. Everything visible is a required parameter you supply, already localized.
- **Not a record of what is showing.** The module keeps no list of posted notifications beyond the
  progress-coalescing state, and cannot tell you whether an id is currently on screen — `cancel` is
  a fire-and-forget request, exactly as it is on both platforms.
- **Not rich notification content.** No big-picture or inbox styles, no images, no reply inputs, no
  grouping/summary API, no badges. Those are deep, platform-shaped features; the module covers title,
  body, icon, progress, sound, importance, buttons and a tap target.
- **Not tied to Compose or any UI framework.**

## When to use it

Use it when shared Kotlin code is the place that decides *what* the user should be told and *when* —
a download's progress, a finished import, a locally-computed reminder — and you want that decision
written once instead of twice, with the failure paths visible.

If only your Android code ever posts notifications, `NotificationCompat` directly is less
indirection. The module pays for itself the moment the same decision has to hold on both platforms,
or the moment you want a test to assert what a screen would have posted when the permission is
denied — which no emulator will hand you.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working notifier on both platforms in five minutes
- [`03-guide.md`](03-guide.md) — progress, action buttons, the tap target, channels you cannot change, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — the channel/category divergence, permissions, the manifest
- [`06-testing.md`](06-testing.md) — `kmptoolkit-notification-testing` and what to assert with it
