# kmptoolkit-scheduler — Getting started

A reminder that fires at a wall-clock time on both platforms, in about five minutes.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-scheduler:<version>")
        }
    }
}
```

Test fixtures are a separate artifact — see [`06-testing.md`](06-testing.md).

## 2. Declare the Android permission (or decide not to)

The library's manifest declares **no permission**. To get exact alarms on Android 12 and later, add
one to your **app's** manifest:

```xml
<!-- User-grantable; the user can revoke it in Settings > Apps > Alarms & reminders. -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

Skipping this is a legitimate choice: alarms are still armed, just inexactly, and `schedule`
returns `AlarmScheduleResult.Inexact` so you know. Read
[`05-platform-notes.md`](05-platform-notes.md#the-exact-alarm-permission) before deciding — the
permission has Play Store consequences, and `USE_EXACT_ALARM` may be the right one instead.

Nothing else goes into your manifest. The broadcast receiver that delivers fired alarms is declared
by the library.

## 3. Write a handler (Android)

A handler runs at fire time on Android and turns the alarm into whatever you want the user to see.

```kotlin
class StudyReminderHandler(private val context: Context) : AlarmHandler {

    override val type: String = "STUDY_REMINDER"

    override suspend fun onFire(alarm: ScheduledAlarm) {
        NotificationManagerCompat.from(context).notify(
            alarm.id.hashCode(),
            NotificationCompat.Builder(context, alarm.notification.channelId)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(alarm.notification.title)
                .setContentText(alarm.notification.body)
                .build(),
        )
    }
}
```

Creating the notification channel, holding `POST_NOTIFICATIONS`, and building the tap intent are
your app's job — this module has no opinion about any of them.

## 4. Create the scheduler

**Android — in `Application.onCreate`, not later.** An alarm can fire in a process that was created
for it, where nothing but `onCreate` has run; a scheduler created lazily registers its handlers too
late and the alarm is dropped.

```kotlin
class App : Application() {

    lateinit var scheduler: AlarmScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        scheduler = createAlarmScheduler(
            context = this,
            handlers = listOf(StudyReminderHandler(this)),
        )
    }
}
```

**iOS — anywhere you build your object graph.** There are no handlers: iOS runs none of your code
at fire time.

```kotlin
val scheduler: AlarmScheduler = createAlarmScheduler(
    soundResolver = AlarmSoundResolver { channelId ->
        if (channelId == "reminders") "reminder.caf" else null
    },
)
```

Ask for notification authorization in your iOS layer as you normally would, before scheduling
anything. This module never shows a system prompt.

## 5. Schedule

```kotlin
val result: AlarmScheduleResult = scheduler.schedule(
    ScheduledAlarm(
        id = "study-$lessonId",
        type = "STUDY_REMINDER",
        fireAtEpochMillis = fireAtEpochMillis,
        notification = AlarmNotification(
            title = strings.reminderTitle,
            body = strings.reminderBody(lessonName),
            channelId = "reminders",
        ),
        payload = mapOf("lessonId" to lessonId),
    )
)

when (result) {
    AlarmScheduleResult.Exact -> Unit
    is AlarmScheduleResult.Inexact -> markMayBeLate(result.reason)
    is AlarmScheduleResult.Failed -> markNotScheduled(result.reason)
}
```

Cancelling takes the same id:

```kotlin
scheduler.cancel("study-$lessonId")
scheduler.cancelAll(myReminderStore.allIds())
```

## 6. Remember what you scheduled

The scheduler stores nothing. Keep your own list of the alarms that ought to exist — you need it to
cancel them, to re-arm them after a reboot, and to reconcile after the app was offline or
force-stopped. See [`03-guide.md`](03-guide.md#owning-the-desired-state).

## Next

- [`03-guide.md`](03-guide.md) — results, reboot, reconciliation, common mistakes
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform actually guarantees
