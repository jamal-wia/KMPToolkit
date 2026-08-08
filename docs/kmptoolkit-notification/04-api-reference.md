# kmptoolkit-notification — API reference

Package `io.github.jamal_wia.kmptoolkit.notification`. Everything below is public and covered by the
ABI dump in `kmptoolkit-notification/api/`.

## `Notifier`

```kotlin
public interface Notifier {
    public suspend fun post(id: String, notification: LocalNotification): NotificationResult
    public fun cancel(id: String)
    public fun cancelAll()
}
```

The one type shared code depends on.

- **`post`** — posts `notification` under `id`, replacing whatever is showing under that id. Every
  *runtime* failure is a `NotificationResult`, never an exception. The single exception is a **blank
  `id`**, which throws `IllegalArgumentException`: it is a bug in the calling code, and left
  unchecked the platforms disagree destructively about it — Android folds it onto a shared integer
  id, `UNNotificationRequest` raises an Objective-C exception that Kotlin/Native cannot catch and
  the process dies. Suspending because checking authorization is asynchronous on iOS; on Android it
  does not suspend in practice.
- **`cancel`** — removes the notification under `id` and forgets its progress-coalescing state. A
  no-op for an id that is not showing, including one this instance never posted. Reports nothing,
  because neither platform reports anything.
- **`cancelAll`** — removes every notification the app is showing, including ones posted by a
  previous process. Does not touch notifications scheduled for later.

**Thread-safety:** safe from any thread, including concurrently for different ids.

**Ordering:** the checks run permission → app-level toggle → channel → icon → coalescing, so the
result names the first reason the user would not have seen the notification. A coalesced post never
hides a real failure.

## Factories

```kotlin
// androidMain
public fun createNotifier(
    context: Context,
    permissionHandler: PermissionHandler,
    config: NotificationConfig = NotificationConfig(),
): Notifier

// iosMain
public fun createNotifier(
    permissionHandler: PermissionHandler,
    config: NotificationConfig = NotificationConfig(),
): Notifier
```

Two signatures rather than one `expect fun`, because Android genuinely needs a `Context` and iOS
does not — see [`docs/01-architecture.md`](../01-architecture.md). Only the application context is
retained on Android, so passing an `Activity` leaks nothing.

```kotlin
public fun noOpNotifier(): Notifier
```

Posts nothing and reports `NotificationResult.NotificationsDisabled`. Stateless and shared; inject it
when the user has notifications off in your own settings.

## `LocalNotification`

```kotlin
public data class LocalNotification(
    public val title: String,
    public val body: String,
    public val channel: NotificationChannelSpec,
    public val icon: NotificationIcon = NotificationIcon.Default,
    public val progress: NotificationProgress? = null,
    public val ongoing: Boolean = false,
    public val autoCancel: Boolean = true,
    public val actions: List<NotificationAction> = emptyList(),
    public val iosCategoryId: String? = null,
    public val contentExtras: Map<String, String>? = null,
)
```

| Property | Android | iOS |
|---|---|---|
| `title`, `body` | rendered | rendered |
| `channel` | see below | partly — see below |
| `icon` | small icon | ignored (app icon always) |
| `progress` | progress bar | ignored for display; drives coalescing |
| `ongoing` | not dismissible | ignored |
| `autoCancel` | dismiss on tap | ignored (always dismisses) |
| `actions` | buttons | ignored — use `iosCategoryId` |
| `iosCategoryId` | ignored | `categoryIdentifier` |
| `contentExtras` | tap opens launcher with extras | ignored — handle in your delegate |

All user-visible strings are already localized by you.

## `NotificationProgress`

```kotlin
public sealed interface NotificationProgress {
    public data object Indeterminate : NotificationProgress
    public data class Determinate(public val percent: Int) : NotificationProgress
}
```

`percent` is clamped to `0..100` for both rendering and coalescing. `null` progress means no bar at
all, and is the right value for a terminal frame.

## `NotificationChannelSpec`

```kotlin
public data class NotificationChannelSpec(
    public val id: String,
    public val name: String,
    public val description: String = "",
    public val importance: NotificationImportance = NotificationImportance.Default,
    public val sound: NotificationSound = NotificationSound.Default,
)
```

Throws `IllegalArgumentException` if `id` or `name` is blank.

`name` and `description` have no defaults on purpose: they are user-visible on Android, and this
library ships no copy. On Android, `importance` and `sound` apply **only when the channel is first
created** — see [`05-platform-notes.md`](05-platform-notes.md) for what each platform does with each
field.

```kotlin
public enum class NotificationImportance { Low, Default, High }
```

Android `IMPORTANCE_LOW` / `_DEFAULT` / `_HIGH`; iOS interruption level `passive` / `active` /
`active` — `High` is not `timeSensitive`, which needs an Apple-granted entitlement.

## `NotificationSound`

```kotlin
public sealed interface NotificationSound {
    public data object Silent : NotificationSound
    public data object Default : NotificationSound
    public data class Custom(public val resourceName: String) : NotificationSound
}
```

`Custom` throws `IllegalArgumentException` on a blank name. The name is `res/raw/<name>` without an
extension on Android and a bundle filename *with* its extension on iOS; the two forms differ, so a
cross-platform caller usually builds this per platform.

## `NotificationIcon`

```kotlin
public sealed interface NotificationIcon {
    public data object Default : NotificationIcon
    public data class AndroidDrawable(public val resourceId: Int) : NotificationIcon
}
```

Android-only, and named so. `Default` is a stock platform drawable, present so a notification always
has a valid icon; replace it before shipping. A `resourceId` that does not resolve comes back as
`NotificationResult.Failed`, checked before anything is posted.

## `NotificationAction`

```kotlin
public data class NotificationAction(public val id: String, public val label: String)
```

Throws `IllegalArgumentException` on a blank `id`. Renders as a button on Android; on iOS buttons
come from the category named by `LocalNotification.iosCategoryId`. No icon field: Android 7+ does not
render action icons on phones, and this library has no artwork to offer.

## `NotificationActionIntent` (Android only)

```kotlin
public object NotificationActionIntent {
    public const val EXTRA_ACTION_ID: String
    public const val EXTRA_NOTIFICATION_ID: String
    public fun actionId(intent: Intent): String?
    public fun notificationId(intent: Intent): String?
    public fun defaultAction(context: Context): String
}
```

The consumer's half of the button seam. `actionId` / `notificationId` return `null` for an intent
that did not come from this module. `defaultAction` returns
`<applicationId>.KMPTOOLKIT_NOTIFICATION_ACTION`, the value your manifest spells with the
`${applicationId}` placeholder.

## `NotificationConfig`

```kotlin
public data class NotificationConfig(
    public val actionBroadcastAction: String? = null,
    public val progressBucketPercent: Int = DEFAULT_PROGRESS_BUCKET_PERCENT,   // 10
    public val minProgressInterval: Duration = DEFAULT_MIN_PROGRESS_INTERVAL,  // 500 ms
)
```

Throws `IllegalArgumentException` for a blank `actionBroadcastAction`, a
`progressBucketPercent` outside `1..100`, or a negative `minProgressInterval`. `null` for the action
derives it from the consumer's application id, so two apps never collide.

Companion: `DEFAULT_PROGRESS_BUCKET_PERCENT: Int`, `DEFAULT_MIN_PROGRESS_INTERVAL: Duration`.

## `NotificationResult`

```kotlin
public sealed interface NotificationResult {
    public data object Posted : NotificationResult
    public data object Coalesced : NotificationResult
    public data object PermissionDenied : NotificationResult
    public data object NotificationsDisabled : NotificationResult
    public data class ChannelBlocked(public val channelId: String) : NotificationResult
    public data class Failed(public val cause: Throwable?) : NotificationResult
}

public val NotificationResult.isPosted: Boolean
```

| Case | Meaning | Platforms |
|---|---|---|
| `Posted` | accepted by the platform (not "drawn") | both |
| `Coalesced` | redundant progress update, previous frame stands | both |
| `PermissionDenied` | no runtime grant | Android 13+, iOS |
| `NotificationsDisabled` | app-level toggle off | Android (iOS folds this into `PermissionDenied`) |
| `ChannelBlocked` | channel muted by the user, or its group muted (API 28+) | Android 8+ |
| `Failed` | icon that does not resolve, framework refusal, `NSError` | both |

`Failed.cause` is for logs. The concrete throwable type is not part of the contract — on iOS it
carries the `NSError`'s localized description.

`isPosted` is true for `Posted` only; `Coalesced` is deliberately excluded, because nothing was
handed to the platform.
