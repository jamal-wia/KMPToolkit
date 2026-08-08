# kmptoolkit-scheduler — Platform notes

What each platform actually does, what it actually guarantees, and what you have to put in your own
app.

## Permissions and manifest entries

### The exact-alarm permission

**This library declares no permission.** Per
[`../01-architecture.md`](../01-architecture.md#android-manifests), a permission in a library
manifest is merged into every consumer's app silently — and this particular one has to be justified
in a Play Store listing, which is not a decision a library gets to make on your behalf.

To get exact alarms on **Android 12 (API 31) and later**, declare one of these in **your app's**
manifest:

| Permission | Granted | Use when |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | By the user, in Settings > Apps > *your app* > Alarms & reminders. Auto-granted on install for API 33+ apps, but revocable at any time. | The user schedules the alarms themselves — reminders, timers, task deadlines. |
| `USE_EXACT_ALARM` | At install, not revocable. | The app's core purpose *is* alarms or calendar-style notifications. Google Play restricts it to those categories and will reject a listing that claims it otherwise. |

Neither is needed below API 31 — the scheduler does not even check there.

**Not declaring either is a supported configuration.** Alarms are still armed, through
`setAndAllowWhileIdle`, and `schedule` returns
`AlarmScheduleResult.Inexact(EXACT_ALARM_PERMISSION_MISSING)` so you can decide what that means for
your feature. Nothing is silently downgraded behind your back.

The permission can also disappear *between* the check and the call — Android 12+ lets the user
revoke it at any moment. That case is caught, the alarm is armed inexactly instead of lost, and the
result is `Inexact(EXACT_ALARM_PERMISSION_REVOKED)`. Both paths are covered by the module's own
Robolectric tests.

To send the user to the setting:

```kotlin
startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
```

### Other permissions you may need

Neither is used by this module, but a realistic reminder feature needs both:

- **`POST_NOTIFICATIONS`** (Android 13+) — your `AlarmHandler` posts the notification, so your app
  requests this.
- **`RECEIVE_BOOT_COMPLETED`** — only if you re-arm alarms after a reboot; see
  [Reboot](#reboot) below.

### The broadcast receiver

The library **does** declare one thing:

```xml
<receiver
    android:name="io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver"
    android:exported="false" />
```

This is a deliberate exception to "the library manifest stays empty", and it is a different kind of
thing from a permission:

- A `<receiver>` grants the app no capability, requests nothing from the user, and appears in no
  Play Store disclosure.
- `exported="false"` means only this process can trigger it, and in practice only the
  `PendingIntent`s this module creates do.
- The alternative — documenting a `<receiver>` block for every consumer to copy — fails silently
  when someone forgets it: no compile error, no exception, `schedule` still reports success, and
  the alarm simply never arrives. That is the worst possible failure mode for a scheduling library.

If you need the alarm broadcast to go somewhere else entirely, do not use the module's receiver:
schedule nothing through it, declare your own receiver, and use `handlerFor` for the dispatch rule.

### iOS

No `Info.plist` entry is required. Notification authorization
(`UNUserNotificationCenter.requestAuthorization`) is your app's job — this module never shows a
system prompt. Scheduling while authorization is **denied** returns
`Failed(NotificationPermissionDenied)` and arms nothing.

`NotDetermined` and `Provisional` are *not* treated as denied: the app may still ask, and
provisional authorization does deliver notifications quietly, so refusing either would throw away
an alarm the OS would have honored.

## Delivery guarantees

### Android

The scheduler uses `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` when permitted and
`setAndAllowWhileIdle(RTC_WAKEUP, ...)` when not. What that buys you:

- **`setExactAndAllowWhileIdle`** fires at the requested wall-clock time and is exempt from Doze.
  The OS still rate-limits it to roughly one alarm per app per nine minutes when the device is
  idle, so a burst of alarms minutes apart may be spread out.
- **`setAndAllowWhileIdle`** fires during Doze too, but at a time the OS chooses within its own
  window — expect drift of minutes, not hours. It is used instead of plain `set` precisely for
  that: plain `set` is deferred to the next Doze maintenance window and can land hours late.
- **`RTC_WAKEUP`** wakes the device, and the time base is wall-clock — so changing the system clock
  or time zone moves the alarm. If your reminder is "07:00 local time", re-schedule on
  `ACTION_TIMEZONE_CHANGED`.

What breaks delivery entirely:

- **Reboot.** All alarms are dropped. See below.
- **Force-stop.** The user stopping the app from Settings cancels its alarms until the app is
  launched again.
- **App standby buckets and battery saver.** A rarely used app is throttled; `AllowWhileIdle`
  mitigates but does not remove this.
- **Aggressive OEM battery management** (Xiaomi, Huawei, Samsung and others) can suppress alarms for
  apps the user has not whitelisted. Nothing in the Android API can override it.

At fire time your `AlarmHandler` runs inside `goAsync()`, which keeps the process alive for roughly
ten seconds. It is enough to post a notification or enqueue `WorkManager`; it is not enough for
network calls.

### iOS

There is **no code execution** at fire time. `UNUserNotificationCenter` presents the notification you
supplied at schedule time, and that is the entire mechanism — nothing of yours runs, so nothing can
be computed, localized, or decided then. This is not a limitation of this module; iOS has no
alarm-style API that runs a killed app's code, and `BGTaskScheduler` (which the module deliberately
does not use) offers opportunistic background execution, not a wall-clock trigger.

Practical consequences:

- **Roughly 64 pending local notifications per app.** Requests past the cap are refused — you get
  `Failed(PlatformError(...))`. Schedule a rolling window rather than a year of reminders.
- **Delivery is at the requested time**, subject to the usual system discretion (Focus modes,
  Scheduled Summary, Do Not Disturb can delay or bundle the *presentation*).
- **The text is frozen.** Language changes after scheduling do not update pending notifications;
  re-schedule them.
- **A trigger in the past is clamped** to one second from now, because iOS rejects a non-positive
  interval outright. A missed reminder therefore appears immediately rather than throwing.
- `Exact` on iOS means "the OS accepted the request for that instant" — it is not the same
  guarantee as an Android exact alarm, because it is a presentation, not an execution.

## Reboot

Android drops every alarm on reboot; iOS keeps pending local notifications across reboot.

This module re-arms nothing on either platform, and stores nothing that would let it. Re-arming is
the consumer's job because the consumer is the only one who has the list of alarms that should
exist, and the only one who knows whether they are still wanted after the device was off for a
week. The mechanics — a `RECEIVE_BOOT_COMPLETED` receiver that re-schedules from your own storage —
are a dozen lines and are spelled out in [`03-guide.md`](03-guide.md#reboot-re-arming).

Putting that receiver in the library would mean merging `RECEIVE_BOOT_COMPLETED` into every
consumer's manifest (exactly what this repository forbids) and adding a persistence layer to a
module whose entire value is not having one.

## Behavior that is identical on both platforms

Everything in `commonMain`: the shape of `ScheduledAlarm` and its blank-id/blank-type validation,
`AlarmSchedulerConfig` validation, replacement-by-id semantics, `cancel` being a no-op for an
unarmed id, `cancelAll` cancelling exactly the ids passed, and the `handlerFor` dispatch rule. One
shared test suite runs against both targets.
