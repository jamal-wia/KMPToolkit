# kmptoolkit-scheduler — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.scheduler`, and the contract it holds to.

## Factories

The two platforms need different inputs, so there is no common factory — construct the scheduler in
platform code and pass the `AlarmScheduler` interface around.

### `createAlarmScheduler` (Android)

```kotlin
public fun createAlarmScheduler(
    context: Context,
    handlers: List<AlarmHandler> = emptyList(),
    config: AlarmSchedulerConfig = AlarmSchedulerConfig(),
): AlarmScheduler
```

Creates the `AlarmManager`-backed scheduler **and registers `handlers`** for fired alarms.
Registration is process-scoped: a `BroadcastReceiver` is instantiated by the framework and cannot
be given dependencies any other way.

- **Call it from `Application.onCreate`.** An alarm may fire in a process created for that alarm.
- Only `context.applicationContext` is retained, so passing an `Activity` leaks nothing.
- Calling it twice **replaces** the previous registration; the earlier handler list stops receiving
  alarms. Pass every handler to one call.

### `createAlarmScheduler` (iOS)

```kotlin
public fun createAlarmScheduler(
    soundResolver: AlarmSoundResolver = AlarmSoundResolver { null },
    config: AlarmSchedulerConfig = AlarmSchedulerConfig(),
): AlarmScheduler
```

Creates the `UNUserNotificationCenter`-backed scheduler. No handlers: iOS runs none of your code at
fire time. It never requests notification authorization — that stays your app's call.

## `AlarmScheduler`

```kotlin
public interface AlarmScheduler {
    public suspend fun schedule(alarm: ScheduledAlarm): AlarmScheduleResult
    public suspend fun cancel(id: String)
    public suspend fun cancelAll(ids: Collection<String>)
}
```

Stateless: it remembers nothing, persists nothing, and cannot enumerate armed alarms.
Implementations are safe to call from any thread.

| Member | Contract |
|---|---|
| `schedule` | Arms `alarm`, replacing any pending alarm with the same `id`. A fire time in the past is legal — Android fires it immediately, iOS clamps the trigger to one second out. Never throws for a permission or platform refusal; those come back in the result. |
| `cancel` | Cancels `id`. Cancelling an id that is not armed is a no-op, not an error — neither platform can tell those apart. |
| `cancelAll` | Cancels exactly the ids passed, in order. An empty collection is a no-op. It is **not** "cancel everything". |

## `ScheduledAlarm`

```kotlin
public data class ScheduledAlarm(
    val id: String,
    val type: String,
    val fireAtEpochMillis: Long,
    val notification: AlarmNotification,
    val payload: Map<String, String> = emptyMap(),
)
```

| Parameter | Contract |
|---|---|
| `id` | Identity. Replacement and cancellation key on it. **Must not be blank** — an `IllegalArgumentException` is thrown at construction otherwise. |
| `type` | Routing key, matched exactly and case-sensitively against `AlarmHandler.type`. **Must not be blank.** |
| `fireAtEpochMillis` | Milliseconds since the Unix epoch, UTC. A `Long` rather than a date-time type so the module needs no date-time dependency. Past values are accepted. |
| `notification` | What the OS shows. Required because iOS must be given the text at schedule time. |
| `payload` | Carried through to the tap: Android intent extras, iOS `userInfo`. Keys must not collide with `config.alarmIdKey` / `alarmTypeKey`, which share the same flat dictionary on iOS. |

## `AlarmNotification`

```kotlin
public data class AlarmNotification(
    val title: String,
    val body: String,
    val channelId: String,
)
```

Strings you supply, already localized — this module never generates or translates text. No sound
field by design: Android takes the sound from the channel, iOS resolves it through
`AlarmSoundResolver`. The module neither creates nor validates the channel.

## `AlarmScheduleResult`

```kotlin
public sealed interface AlarmScheduleResult {
    public data object Exact : AlarmScheduleResult
    public data class Inexact(val reason: InexactReason) : AlarmScheduleResult
    public data class Failed(val reason: AlarmFailure) : AlarmScheduleResult
}
```

| Case | Armed? | Produced when |
|---|---|---|
| `Exact` | yes | Android accepted `setExactAndAllowWhileIdle`, or iOS accepted the notification request. |
| `Inexact` | yes | Android could not schedule exactly and fell back to `setAndAllowWhileIdle`. |
| `Failed` | **no** | Nothing was armed. |

## `InexactReason`

| Value | Meaning |
|---|---|
| `EXACT_ALARM_PERMISSION_MISSING` | `canScheduleExactAlarms()` was false: the permission is not declared or not granted. |
| `EXACT_ALARM_PERMISSION_REVOKED` | The permission was reported as held and the exact call was still rejected — the Android 12+ check-then-act race, which the caller cannot close. |

Android-only; iOS never returns `Inexact`.

## `AlarmFailure`

| Case | Meaning |
|---|---|
| `NotificationPermissionDenied` | iOS notification authorization is denied, so a scheduled notification would be discarded. Nothing was armed. |
| `SchedulerUnavailable` | The platform scheduling service could not be obtained (`ALARM_SERVICE` returned nothing). Not expected on a healthy device. |
| `PlatformError(message: String?)` | The platform rejected the request for another reason — e.g. iOS's cap of roughly 64 pending local notifications. `message` is the platform's own untranslated diagnostic, for logs only. |

## `AlarmHandler`

```kotlin
public interface AlarmHandler {
    public val type: String
    public suspend fun onFire(alarm: ScheduledAlarm)
}
```

**Android only.** Called at fire time on a background dispatcher, inside a broadcast window of
roughly ten seconds; do the minimum and return. It may run in a freshly created process where only
`Application.onCreate` has executed. A throwable escaping `onFire` crashes the process.

## `handlerFor`

```kotlin
public fun Collection<AlarmHandler>.handlerFor(type: String): AlarmHandler?
```

The dispatch rule the receiver uses, public so you can reuse it if you route alarms yourself.
`null` means the alarm is dropped — a fired alarm cannot be retried. If two handlers share a type
(a configuration mistake) the first wins; it is not a priority mechanism.

## `AlarmSchedulerConfig`

```kotlin
public data class AlarmSchedulerConfig(
    val alarmIntentScheme: String? = null,
    val alarmIdKey: String = DEFAULT_ALARM_ID_KEY,   // "alarm_id"
    val alarmTypeKey: String = DEFAULT_ALARM_TYPE_KEY, // "alarm_type"
)
```

Every identifier this module writes into a platform artifact, so none of them is a name the library
invented for you.

| Parameter | Contract |
|---|---|
| `alarmIntentScheme` | **Android only.** URI scheme for the per-alarm data URI that keeps two alarms' `PendingIntent`s distinct. `null` derives `<applicationId>.alarm`, with characters a URI scheme cannot hold (`_`, non-ASCII) replaced by `-`. A non-null value must be a valid URI scheme. |
| `alarmIdKey` | Key carrying `ScheduledAlarm.id`: an intent extra on Android, a `userInfo` entry on iOS. |
| `alarmTypeKey` | Key carrying `ScheduledAlarm.type`, on the same terms. |

Throws `IllegalArgumentException` at construction if a key is blank, the two keys are equal, or the
scheme is non-null and invalid. There is no iOS background-task id here because the module registers
none — iOS scheduling goes through `UNUserNotificationCenter`.

## `AlarmSoundResolver`

```kotlin
public fun interface AlarmSoundResolver {
    public fun soundFor(channelId: String): String?
}
```

Consumer-supplied SPI, called **only on iOS**, synchronously on the scheduling thread, once per
`schedule`. Returns a bundled sound filename including its extension (`"reminder.caf"`) or `null`
for the platform default. An unresolvable name falls back to the default sound.

## Not public

`AlarmReceiver`, `AlarmIntents`, and the process-scoped handler registration are `internal`. The
receiver is declared in the library's manifest as `exported="false"` and is triggered only by
`PendingIntent`s this module created — see
[`05-platform-notes.md`](05-platform-notes.md#the-broadcast-receiver).
