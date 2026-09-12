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
every activity in the process. An untracked activity resuming does **not** displace the tracked one:
`withActivity` keeps answering with the tracked activity underneath, and stops only when that one
goes away. See [`03-guide.md`](03-guide.md) for when to narrow it.
