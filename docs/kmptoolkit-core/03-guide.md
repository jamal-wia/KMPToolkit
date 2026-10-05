# kmptoolkit-core — Guide

The only scenario in which application code meets this artifact is the opt-in error described in
[`02-getting-started.md`](02-getting-started.md). The rest of this page is for contributors to the
suite.

## Adding a cross-module internal symbol

1. Declare it `public` in the module that owns it and annotate it `@ToolkitInternalApi`. Anything
   that only its own module needs stays `internal`.
2. Opt in at the narrowest scope that works — a class-level or file-level
   `@OptIn(ToolkitInternalApi::class)`, as `EngineVideoPlayer` and `VideoPlayerSurface` do.
3. Document it under the owning module's `04-api-reference.md` in a `@ToolkitInternalApi` section,
   not in the consumer-facing tables.
4. A symbol that more than one module needs and that has no natural owner belongs here — but only
   once a second module needs it, and only with its own tests in `commonTest`.

## Using `StateMachineLock`

```kotlin
@OptIn(ToolkitInternalApi::class)
internal class Machine {
    private val gate: StateMachineLock = StateMachineLock()

    // The owner's own API: blocks until the lock is free, so the transition has happened when it returns.
    fun pause(): Unit = gate.exclusive { /* read state, call the engine, write state */ }

    // An engine callback or a tick: never blocks, runs now or is handed to the current holder.
    fun onEngineEvent(): Unit = gate.submit { /* the same kind of transition */ }
}
```

Rules of thumb:

- Never call a blocking or slow operation inside `exclusive` if a callback or a main-thread call
  could be waiting on the lock; do the slow part outside it and take the lock again to publish the
  result.
- Never `submit` from code that needs the result to have been applied when it returns: when the lock
  is held elsewhere, `submit` returns first.
- An exception thrown by a queued action is rethrown by the call that drained the queue; the actions
  behind it still run. See the contract in [`04-api-reference.md`](04-api-reference.md).
