# kmptoolkit-scheduler — Overview

Exact-time, one-shot local alarms from shared Kotlin code: `AlarmManager` on Android,
`UNUserNotificationCenter` on iOS, behind one `AlarmScheduler` interface that tells you what the OS
actually granted.

## The problem it solves

Some events have to happen at a wall-clock instant on the device itself — a medication reminder at
08:00, a study session at 19:30, an appointment alert twenty minutes ahead. A server push cannot be
relied on for these: the device may be offline, the push may be delayed or dropped, and the whole
point is that the reminder works on a plane. A foreground timer cannot be relied on either: the app
will be backgrounded and eventually killed.

The only mechanism that survives both is the OS's own scheduler — you hand it the event ahead of
time and it wakes something up. That mechanism looks completely different on each platform:

- **Android** arms a `PendingIntent` with `AlarmManager` and **runs your code** in a short broadcast
  window at fire time.
- **iOS** accepts a pre-rendered local notification and **runs nothing of yours** — the OS shows
  what you handed it at schedule time.

This module hides the API difference without hiding that behavioral one, which is why
`ScheduledAlarm` carries a fully rendered `AlarmNotification` (iOS needs it up front) *and* a
`type` routed to an `AlarmHandler` (Android can do better than a frozen string).

```kotlin
val result: AlarmScheduleResult = scheduler.schedule(
    ScheduledAlarm(
        id = "reminder-42",
        type = "STUDY_REMINDER",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = body, channelId = "reminders"),
        payload = mapOf("lessonId" to "42"),
    )
)
```

`result` is not a `Boolean`. Android 12+ can refuse exact scheduling, so the answer is one of
"armed exactly", "armed but the OS may deliver it minutes late, and here is why", or "not armed at
all, and here is why". Only the caller knows whether a downgrade is acceptable — see
[`03-guide.md`](03-guide.md#deciding-what-to-do-about-an-inexact-result).

Dependencies: `kotlinx-coroutines-core` and nothing else. No DI framework, no notification library,
no Compose.

## What this is **not**

This is the shortest possible mechanism for "wake something up at 08:00". Almost everything nearby
is out of scope, deliberately:

- **Not a task runner or job scheduler.** It does not run background work, does not retry, has no
  constraints (network, charging, idle), and no notion of a job succeeding or failing. If you need
  "sync when there is Wi-Fi, eventually", you want `WorkManager` / `BGTaskScheduler`, not this.
- **Not an outbox.** It carries no queue, no delivery guarantee, and no persistence. An alarm that
  fires is delivered once to a handler that either does something or does not; nothing is retried
  and nothing is recorded.
- **Not a repeating scheduler.** Every alarm is one-shot. There is no interval, no cron expression,
  no calendar rule. Repetition is the caller's job: at fire time, schedule the next occurrence.
  That is a deliberate omission — repeating alarms drift, break across time-zone and DST changes,
  and every app wants slightly different semantics for a missed occurrence.
- **Not a store of what you scheduled.** The scheduler is stateless. It cannot list armed alarms,
  cannot tell you whether an id is armed, and `cancelAll(ids)` cancels exactly the ids you pass —
  because `AlarmManager` cannot enumerate its own alarms and this module adds no database to
  compensate. **You own the list of alarms that ought to exist**, in whatever storage you already
  have.
- **Not reboot-persistent, and it does not re-arm anything.** Android drops all alarms on reboot.
  Since this module persists nothing, it has nothing to restore — re-arming is the consumer's
  responsibility, and it is a few lines given that you already own the desired-state list: declare
  a `RECEIVE_BOOT_COMPLETED` receiver in your app and re-schedule from your own storage. See
  [`05-platform-notes.md`](05-platform-notes.md#reboot). A boot receiver inside the library would
  need its own permission in the merged manifest and its own persistence layer, to re-arm a list it
  cannot know is still correct.
- **Not a notification library.** It creates no channels, requests no permissions, shows no system
  prompt, and renders nothing on Android — your `AlarmHandler` posts the notification with whatever
  notification code you already have. On iOS the OS renders what you supplied; this module never
  supplies text of its own.
- **Not a permission requester.** It never asks the user for anything. It reports what is missing
  through typed results and leaves both the asking and the copy to you.
- **Not a guarantee of punctuality.** It is a request to the OS, and the OS decides. Doze, battery
  saver, standby buckets, force-stop, and a revoked permission all bend or break delivery — see
  [`05-platform-notes.md`](05-platform-notes.md) for what each platform actually promises.
- **Not a way to run code at a time on iOS.** There is no such mechanism on iOS for a killed app.
  What you get there is a notification the OS displays. If your feature needs computation at fire
  time, it has to be designed to survive that.

## When to use it

Use it when a user-visible event must happen at a specific wall-clock time on the device, works
offline, and survives the app being killed — reminders, alerts, scheduled prompts.

Do not use it for background processing, for anything that can be a push notification, or for
anything that can wait until the app is next opened.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — wiring both platforms, end to end
- [`03-guide.md`](03-guide.md) — handlers, results, reboot re-arming, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, the manifest, delivery guarantees
- [`06-testing.md`](06-testing.md) — `kmptoolkit-scheduler-testing` and what to assert
