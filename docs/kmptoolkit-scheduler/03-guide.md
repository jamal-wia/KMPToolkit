# kmptoolkit-scheduler — Guide

Everything past the first working alarm: what to do with a degraded result, who owns the list of
alarms, how a fired alarm reaches your code, and the mistakes that are silent in production.

## Owning the desired state

The scheduler is stateless on purpose. It cannot list what is armed, cannot answer "is `reminder-7`
still scheduled?", and cannot restore anything. Every non-trivial consumer therefore keeps its own
table of the alarms that *ought* to exist — id, fire time, and whatever the notification needs —
and treats the OS as a cache of that table.

That table is what lets you:

- cancel precisely (`cancelAll(store.allIds())`),
- re-arm after a reboot,
- reconcile after the app was force-stopped, restored from a backup, or offline for a week,
- and answer product questions the OS cannot ("show the user their upcoming reminders").

A reconcile pass is usually the simplest correct design: on every app start, cancel everything the
table says is stale and re-schedule everything still in the future. Re-scheduling an id that is
already armed replaces it, so a reconcile pass is idempotent and cheap.

## Deciding what to do about an inexact result

`schedule` returns three shapes:

| Result | Armed? | Meaning |
|---|---|---|
| `AlarmScheduleResult.Exact` | yes | Fires at the requested instant. |
| `AlarmScheduleResult.Inexact(reason)` | yes | Fires, but the OS may delay it — typically minutes. |
| `AlarmScheduleResult.Failed(reason)` | no | Nothing will fire. |

`Inexact` is the interesting one, and only you can judge it:

- A daily study nudge is fine ten minutes late. Record the downgrade, move on.
- A medication reminder is not. Surface it: explain that exact alarms are off and send the user to
  the system setting (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`). The alarm *is* armed meanwhile, so
  the user is not left with nothing while they decide.

Both `InexactReason` values mean the same thing to the user — no exact-alarm permission — but they
happen at different moments: `EXACT_ALARM_PERMISSION_MISSING` is "we checked and it was not
granted", `EXACT_ALARM_PERMISSION_REVOKED` is "it was granted when we checked and gone a moment
later", which Android 12+ genuinely allows. Treat them identically in the UI; the distinction is
for your logs.

`Failed(NotificationPermissionDenied)` on iOS is worth handling explicitly: the alarm did **not**
get armed, so the reminder simply will not happen until the user authorizes notifications.

## How a fired alarm reaches your code (Android)

1. `AlarmManager` fires the `PendingIntent` this module armed.
2. The library's `AlarmReceiver` — declared in the library manifest, `exported="false"` — receives
   the broadcast and rebuilds the `ScheduledAlarm` from the intent.
3. It finds the handler whose `type` equals the alarm's `type` and calls `onFire` on a background
   dispatcher inside `goAsync()`.

The alarm is dropped silently if no handler claims its type, or if no scheduler has been created in
this process yet. Neither logs, throws, or retries — there is no way to un-fire an alarm.

That last case is the one that bites: **create the scheduler in `Application.onCreate`.** Handlers
are registered as part of creating it, and an alarm can fire in a process created for that alarm
alone.

## iOS is a different mechanism, not a different implementation

On iOS nothing of yours runs at fire time. The OS shows the notification you handed it when you
scheduled. Consequences you have to design around:

- **The text is frozen at schedule time.** If the user switches language, an already-scheduled
  notification keeps the old copy. Re-schedule your alarms when the app's language changes.
- **`AlarmHandler` is never called.** Anything you would have computed at fire time must either be
  computed in advance or deferred to the tap.
- **`payload` is all the tap handler gets.** Put the routing information there, ideally under the
  same keys your remote pushes use so one tap handler serves both.

## Sounds

`AlarmNotification` has no sound field. On Android the sound belongs to the notification channel
you created. On iOS the scheduler asks your `AlarmSoundResolver` for a bundled filename per alarm,
keyed by `channelId` — so the same channel vocabulary drives both platforms and the sound is
configured in exactly one place.

```kotlin
createAlarmScheduler(
    soundResolver = AlarmSoundResolver { channelId ->
        when (channelId) {
            "reminders" -> "reminder.caf"
            "alerts" -> "alert.caf"
            else -> null // platform default
        }
    },
)
```

## Reboot re-arming

Android drops every alarm on reboot, and this module re-arms nothing — it has no storage to re-arm
from. Because you already keep the desired-state table, the fix is short and lives in your app:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver android:name=".ReArmAlarmsReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

```kotlin
class ReArmAlarmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminderStore.upcoming().forEach { scheduler.schedule(it.toAlarm()) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

This deliberately stays in your app: the permission would otherwise be merged into every consumer's
manifest, and only you know which alarms are still wanted after however long the device was off.

## Choosing ids and types

- **`id`** is identity. Same id twice means "replace", not "add" — that is the mechanism for
  editing a reminder's time. Derive it from your own domain key (`"reminder-$reminderId"`), never
  from the fire time, or moving a reminder leaves the old alarm armed.
- **`type`** is routing. One stable value per feature, matched exactly and case-sensitively against
  `AlarmHandler.type`. It is not a category for display.

## Common mistakes

- **Creating the scheduler lazily.** Handlers register when the scheduler is created; an alarm that
  fires first finds none and is dropped. `Application.onCreate`, always.
- **Two `createAlarmScheduler` calls with different handler lists.** The last one wins — the first
  list stops receiving alarms. Pass every handler to one call.
- **Treating the result as a boolean.** `Inexact` is armed; `Failed` is not. Collapsing them either
  hides a downgrade or invents a failure that did not happen.
- **Expecting `cancelAll()` to clear everything.** It cancels exactly the ids you pass. There is no
  "cancel all mine" on Android.
- **Assuming an alarm survives force-stop or reboot.** Neither is true on Android. Reconcile at
  startup and re-arm at boot.
- **Scheduling hundreds of alarms on iOS.** Roughly 64 pending local notifications per app, then
  requests are refused — you get `Failed(PlatformError(...))`. Schedule a rolling window, not a
  year of reminders.
- **Localizing at fire time on iOS.** There is no fire time on iOS. Re-schedule on language change.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — the exact contract of every symbol
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, manifest, delivery guarantees
- [`06-testing.md`](06-testing.md) — asserting your scheduling logic without a device
