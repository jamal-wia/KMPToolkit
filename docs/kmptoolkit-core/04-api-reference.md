# kmptoolkit-core — API reference

Every symbol here is `@ToolkitInternalApi`: public because Kotlin has no visibility between "same
module" and "everyone", **not** part of the compatibility promise. Package
`io.github.jamal_wia.kmptoolkit.core`; targets Android, iOS, `jvm`.

## `@ToolkitInternalApi`

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Cross-module internal API — not part of the public contract.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class ToolkitInternalApi
```

Marks API shared between `kmptoolkit-*` modules. May change in any release without a major-version
bump. It was declared in `kmptoolkit-video-player` before 2.2.0; the import is now
`io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi`.

## `StateMachineLock`

```kotlin
@ToolkitInternalApi
public class StateMachineLock {
    public fun <T> exclusive(block: () -> T): T
    public fun submit(action: () -> Unit)
}
```

Serializes every transition of a state machine, whichever thread it arrives on.

| Member | Blocks? | Contract |
|---|---|---|
| `exclusive(block)` | Yes, until the lock is free | Runs `block` holding the lock and returns its value. Reentrant on the same thread. When the outermost section ends, runs whatever was queued meanwhile, before returning. |
| `submit(action)` | Never | Runs `action` now if the lock is free; otherwise queues it for the thread holding the lock, which runs it before letting go. |

Details that tests pin down:

- **Order.** Queued actions run in the order they were queued, including actions queued by a queued
  action (those go to the back).
- **Same-thread submit.** An action submitted from inside an `exclusive` section on the same thread
  is queued, and runs after the section's block, before the outermost `exclusive` returns.
- **Failure.** When a queued action throws, the ones behind it still run. The first failure is then
  rethrown by the call that drained the queue (the outermost `exclusive`, or `submit` when the lock
  was free); later failures are attached to it as suppressed exceptions. When the block of
  `exclusive` itself throws and no queued action fails, the block's exception propagates and the
  queue is still drained.
- **No deadlock with engine locks.** An engine usually reports events while holding a lock of its
  own, and a transition holds this lock while calling into the engine. Because `submit` never waits
  for this lock, those two locks can never be taken in opposite orders and wait on each other.

## `ReentrantLockHandle` and `newReentrantLock()`

```kotlin
@ToolkitInternalApi
public interface ReentrantLockHandle {
    public fun lock()
    public fun unlock()
    public fun tryLock(): Boolean
}

@ToolkitInternalApi
public expect fun newReentrantLock(): ReentrantLockHandle
```

A plain reentrant lock: `java.util.concurrent.locks.ReentrantLock` on Android and `jvm`,
`NSRecursiveLock` on iOS. The building block of `StateMachineLock`; exposed only because it is
`expect`/`actual` public.
