# kmptoolkit-scheduler — Testing

## The fixture artifact

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-scheduler")
    testImplementation("io.github.jamal-wia:kmptoolkit-scheduler-testing")
}
```

`kmptoolkit-scheduler-testing` ships one type, `RecordingAlarmScheduler`. It lives in a separate
artifact for the reason described in
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate-testing-artifacts):
Kotlin Multiplatform cannot expose one module's `commonTest` to a consumer, so a published artifact
is the only mechanism — and a consumer who never writes a test never downloads it.

## What it is for

The interesting logic in an app that uses this library is not the OS call — it is *which* alarms
your code decides to arm and cancel, and what it does when the OS degrades or refuses the request.
`RecordingAlarmScheduler` lets you assert both on a plain JVM test, with no emulator, no device
clock, and no permission state.

```kotlin
@Test
fun `enabling reminders arms one alarm per active reminder`() = runTest {
    val scheduler = RecordingAlarmScheduler()

    ReminderSync(scheduler).apply(reminders = listOf(morning, evening))

    assertEquals(listOf("reminder-morning", "reminder-evening"), scheduler.armed.map { it.id })
}
```

Driving the paths that are awkward on real hardware is the other half:

```kotlin
@Test
fun `a downgraded alarm is surfaced to the user`() = runTest {
    val scheduler = RecordingAlarmScheduler(
        resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) },
    )

    val state = ReminderSync(scheduler).apply(reminders = listOf(medication))

    assertEquals(ReminderState.MayBeLate, state)
}
```

## API

| Member | Meaning |
|---|---|
| `resultFor: (ScheduledAlarm) -> AlarmScheduleResult` | Decides what `schedule` returns. Assignable mid-test, so one instance can succeed for some alarms and fail for others. Defaults to `Exact`. |
| `armed: List<ScheduledAlarm>` | Currently armed alarms, in the order their ids were first scheduled. |
| `scheduleCalls: List<ScheduledAlarm>` | Every `schedule` call in order, including replacements and failures. |
| `cancelledIds: List<String>` | Every id passed to `cancel` / `cancelAll`, including ids that were never armed. |
| `clear()` | Drops recordings and armed alarms; leaves `resultFor` alone. |

It mirrors the real schedulers where it matters: scheduling an id that is already armed **replaces**
it, and an alarm whose result is `Failed` is **not** armed — it appears in `scheduleCalls` only.

**Not thread-safe.** It is meant for one test's coroutine at a time.

## Testing your own `AlarmHandler`

A handler is a plain `suspend` function of a `ScheduledAlarm`; call it directly. Nothing in this
module needs to be running.

```kotlin
@Test
fun `the handler posts a notification on the alarm's channel`() = runTest {
    StudyReminderHandler(context).onFire(alarm)

    assertEquals(1, shadowOf(notificationManager).allNotifications.size)
}
```

Dispatch itself — "does the right handler get this alarm?" — is `handlerFor`, which is public and
pure:

```kotlin
assertSame(studyHandler, handlers.handlerFor("STUDY_REMINDER"))
assertNull(handlers.handlerFor("SOMETHING_ELSE"))
```

## What this module's own tests cover

For reference when judging whether a behavior is already guaranteed:

- **`commonTest`** (runs on JVM and iOS) — `handlerFor` dispatch including unknown types, duplicate
  types, an empty handler list and case sensitivity; `ScheduledAlarm` and `AlarmSchedulerConfig`
  validation; the iOS trigger arithmetic including a past fire time, an exactly-now fire time, and
  a far-future value that must not overflow.
- **`androidUnitTest`** (Robolectric) — arming and cancelling against a real `AlarmManager`,
  replacement by id, two ids whose hash codes collide staying independent, `cancelAll` semantics, a
  fire time in the past, exact scheduling below and above Android S, the missing-permission
  downgrade, the revoked-mid-flight downgrade (via a custom shadow that throws `SecurityException`),
  the derived and configured intent scheme, the intent round trip, and receiver dispatch for a
  matching type, an unknown type, an unregistered process, and an unrelated intent.

The iOS `UNUserNotificationCenter` calls themselves are not unit-tested: they need a real app
bundle and an authorization state, and everything around them that can be wrong on its own — the
trigger arithmetic, the identifiers, the result mapping shape — is covered in common code.
