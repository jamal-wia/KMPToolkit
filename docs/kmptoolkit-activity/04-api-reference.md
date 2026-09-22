# kmptoolkit-activity — API reference

Every symbol here lives in `androidMain`; this module has no other target.

## `ActivityAccess`

```kotlin
public interface ActivityAccess {
    public fun <R> withActivity(block: (Activity) -> R): R?
    public fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription
    public fun release()
}
```

### `withActivity`

Runs `block` with the currently resumed activity and returns its result, or returns `null` without
running it when there is none.

The activity is validated first: one that is finishing or already destroyed is treated as absent and
dropped, so a stale reference cannot reach your code. The block runs on the calling thread.

### `addOnActivityResumedListener`

Subscribes to activity resumption, and fires immediately if one is already resumed — so a listener
that applies state to a window does not need a separate path for "an activity was already there".
Invoked synchronously on the main thread.

The listener is held strongly until the returned subscription is cancelled. Do not let it store the
activity.

### `release`

Unregisters from the `Application`, drops the current activity reference, and forgets every
listener. Idempotent. A process-lifetime instance never needs it; it exists for narrower scopes and
for tests.

## `ActivitySubscription`

```kotlin
public interface ActivitySubscription {
    public fun cancel()
}
```

Stops the listener from being called again and releases whatever it captured. Idempotent.

## `createActivityAccess`

```kotlin
public fun createActivityAccess(
    application: Application,
    isTracked: (Activity) -> Boolean = { true },
): ActivityAccess
```

Creates a process-wide `ActivityAccess`, registered against `application`'s activity lifecycle
callbacks. The parameter is `Application` and not `Context` on purpose: passing an `Activity` to
something that outlives every activity would be a mistake the compiler could not catch.

`isTracked` decides which activities this instance is willing to answer with. The default accepts
every activity in the process. An untracked activity resuming never becomes the answer: your tracked
activity was paused when it was covered, so `withActivity` answers `null` while it is covered and
answers with your activity again when it resumes. See [`03-guide.md`](03-guide.md) for when to narrow it.

## `SystemScreenLauncher`

```kotlin
public fun interface SystemScreenLauncher {
    public fun launch(request: SystemScreenRequest): Boolean

    public companion object {
        public val SeparateTask: SystemScreenLauncher
        public fun callerTask(activityAccess: ActivityAccess): SystemScreenLauncher
    }
}
```

Decides how a module opens a system screen. Called once per logical request, on the caller's thread.
Returns `true` if a screen was handed to the system and `false` if nothing was opened. `true` is not
a guarantee the user saw it: since Android 10 an activity start from the background is blocked
silently, and a start that lock-task mode forbids returns normally too. A launcher that throws is
treated as `false` by the calling module. Since 1.7.0.

### `SeparateTask`

Starts the first resolvable candidate from the application context with
`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`: a task of its own, never your task, and never
an unrelated Settings task left in the background — only a task already rooted at the same screen,
with the same data, is brought forward. Works from any thread. See
[`05-platform-notes.md`](05-platform-notes.md#system-screens-and-tasks) for the flags and their limits.

### `callerTask(activityAccess)`

Starts the first resolvable candidate from the activity `activityAccess` reports as resumed, with no
task flags, so the screen joins your task. Falls back to `SeparateTask` when no activity is resumed,
the resumed one is `singleInstance`, or the call is not on the main thread. If an activity is
resumed but no candidate resolves, the answer is `false` — it does not retry in a separate task,
where the same intents would not resolve either.

## `SystemScreenRequest`

```kotlin
public class SystemScreenRequest(
    candidates: List<Intent>,
    context: Context,
    public val kind: SystemScreenKind,
) {
    public val candidates: List<Intent>
    public val applicationContext: Context
    public fun startFirstResolvable(start: (Intent) -> Unit): Boolean
}
```

One logical request. `candidates` are tried in order and carry no launch flags; the list and every
intent are copied on construction, and each read of `candidates` returns fresh copies. An empty list
throws `IllegalArgumentException`. Only `context.applicationContext` is kept. The constructor is
public so a launcher of your own can be tested.

`startFirstResolvable` calls `start` with a copy of each candidate until one does not throw
`ActivityNotFoundException` or `SecurityException`, and returns whether one succeeded. Any other
exception propagates. There is no `resolveActivity` pre-check, deliberately: which package serves a
Settings screen varies by manufacturer, and starting is the only answer that is always right.

`toString()` is for diagnostics only; its format is not stable.

## `SystemScreenKind`

```kotlin
public interface SystemScreenKind
```

Which screen a request opens. Open on purpose: each module declares its own kinds
(`BiometricEnrollmentScreen`, `LocationSettingsScreen`, `AppDetailsScreen`,
`SpecialPermissionScreen(permission)`), and later releases may add more — match with an `else`
branch. A kind's `toString()` is for diagnostics only.
