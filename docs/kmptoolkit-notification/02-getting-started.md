# kmptoolkit-notification — Getting started

Five minutes to a notification on both platforms.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-notification:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-notification-testing:<version>")
        }
    }
}
```

With the BOM, drop the version — see [`docs/00-getting-started.md`](../00-getting-started.md).

`kmptoolkit-permission` comes along transitively: the factories take a `PermissionHandler`, and you
need one anyway to ask for `POST_NOTIFICATIONS`.

## 2. Declare the permission (Android)

The library declares none. Your app's manifest does:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Nothing to add on iOS — authorization is requested at runtime, and there is no `Info.plist` entry
for it. Full details in [`05-platform-notes.md`](05-platform-notes.md).

## 3. Create the notifier

**Android** — in `Application.onCreate`, or wherever you assemble dependencies:

```kotlin
val permissionHandler: PermissionHandler = createPermissionHandler(
    context = this,
    host = permissionRequestHost,
    activityAccess = activityAccess,
    storage = storage,
)
val notifier: Notifier = createNotifier(context = this, permissionHandler = permissionHandler)
```

**iOS** — from your app delegate or wherever you build shared dependencies:

```kotlin
val notifier: Notifier = createNotifier(permissionHandler = createPermissionHandler())
```

Pass the resulting `Notifier` into shared code as the interface. Shared code never calls the
factory — that is the point of there being two of them (see
[`docs/01-architecture.md`](../01-architecture.md)).

## 4. Ask for the permission, once, when it makes sense

The module never prompts. Do it where your UI can explain why:

```kotlin
val status: PermissionStatus = permissionHandler.request(Permission.NOTIFICATIONS)
```

You can post before asking — you will simply get `NotificationResult.PermissionDenied` back, which
is a perfectly good trigger for showing the prompt at a moment the user is expecting it.

## 5. Post something

```kotlin
val remindersChannel = NotificationChannelSpec(
    id = "reminders",
    name = strings.remindersChannelName,          // shown in Android's system settings
    description = strings.remindersChannelHint,   // shown there too
    importance = NotificationImportance.Default,
)

val result: NotificationResult = notifier.post(
    id = "reminder-42",
    notification = LocalNotification(
        title = strings.timeToStudy,
        body = strings.lessonName,
        channel = remindersChannel,
        contentExtras = mapOf("lessonId" to "42"),  // tapping opens the app with these extras
    ),
)
```

`id` is yours: re-posting it replaces the notification in place, `cancel("reminder-42")` takes it
down. It is never shown to the user.

## 6. Handle the result

```kotlin
when (result) {
    NotificationResult.Posted, NotificationResult.Coalesced -> Unit
    NotificationResult.PermissionDenied -> promptForNotifications()
    NotificationResult.NotificationsDisabled -> showSettingsHint()
    is NotificationResult.ChannelBlocked -> showSettingsHint()
    is NotificationResult.Failed -> logger.error(result.cause) { "notification failed" }
}
```

Ignoring the result compiles and is a reasonable choice for a decorative notification. Ignoring it
*everywhere* is how "notifications don't work" becomes unfixable.

## Where to go next

- Progress bars, action buttons and the tap target: [`03-guide.md`](03-guide.md)
- What each platform actually does with a channel: [`05-platform-notes.md`](05-platform-notes.md)
- Asserting all of this in tests without a device: [`06-testing.md`](06-testing.md)
