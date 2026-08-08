# kmptoolkit-coroutines — Overview

A two-type dispatcher seam: an `AppDispatchers` interface your code depends on, and a
`TestAppDispatchers` double your tests substitute.

## The problem it solves

Code that calls `Dispatchers.IO`, `Dispatchers.Main`, or `Dispatchers.Default` directly is hard to
test: the dispatcher is a hard-wired global, so a test either exercises real background threads
(slow, flaky, order-dependent) or resorts to a global override like
`Dispatchers.setMain()` — which is process-wide state that leaks between tests.

Depending on an injected `AppDispatchers` instead makes the choice a constructor parameter:

```kotlin
class UserRepository(private val dispatchers: AppDispatchers) {
    suspend fun load() = withContext(dispatchers.io) { /* ... */ }
}
```

Production passes `DefaultAppDispatchers()`; tests pass `TestAppDispatchers()`, which collapses all
three dispatchers onto one deterministic `UnconfinedTestDispatcher`.

## What this is **not**

- **Not a coroutine-scope manager.** It does not create, own, or cancel `CoroutineScope`s, and has
  no opinion on structured-concurrency lifecycles. Scope ownership stays with whatever holds the
  lifecycle (your ViewModel, component, or service).
- **Not a replacement for `kotlinx-coroutines-test`.** `TestAppDispatchers` is a thin adapter over
  `UnconfinedTestDispatcher`; you still use `runTest`, `advanceTimeBy`, and the rest of the standard
  test API directly.
- **Not a threading policy.** It doesn't decide which dispatcher a given operation should use — it
  only makes that decision injectable.
- **Not an exception-handling or supervision utility.** No `CoroutineExceptionHandler`, no retry, no
  supervisor wiring.

## When to use it

Use it when you have logic that picks a dispatcher and you want that decision replaceable in tests.

If your code never names a dispatcher — because it's called from an already-dispatched context and
just suspends — you don't need this module.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — scenarios, testing patterns, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
