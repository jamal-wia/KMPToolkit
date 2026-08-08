# kmptoolkit-coroutines — Guide

Scenarios in order of increasing complexity. Each snippet compiles as written.

## Sharing one scheduler across the test

`TestAppDispatchers` creates its own `TestCoroutineScheduler` if you don't pass one. That is fine
for a single object, but the moment two collaborators each get their own `TestAppDispatchers`, they
run on **different** virtual clocks — `advanceTimeBy` on one won't move the other, and a test that
looks correct will hang or see stale state.

Pass one scheduler explicitly whenever more than one object is involved:

```kotlin
val scheduler = TestCoroutineScheduler()
val dispatchers = TestAppDispatchers(scheduler)

val repository = UserRepository(dispatchers)
val syncEngine = SyncEngine(dispatchers)   // same scheduler, same virtual clock

runTest(scheduler) {
    syncEngine.start()
    advanceTimeBy(5.seconds)
    assertEquals(1, repository.loadCount)
}
```

Reusing one `TestAppDispatchers` instance for both collaborators works equally well, and is simpler
when they're constructed in the same place.

## Virtual time and `UnconfinedTestDispatcher`

`TestAppDispatchers` is backed by `UnconfinedTestDispatcher`, which starts coroutines **eagerly** —
a `launch` runs up to its first real suspension point before `launch` returns. This is usually what
you want in a test, because it removes a `runCurrent()` call from every assertion.

If you need the opposite — coroutines that don't start until you say so — use
`StandardTestDispatcher` directly for that test rather than `TestAppDispatchers`; the module
deliberately doesn't offer a switch, because a double whose scheduling semantics vary by
configuration is harder to reason about than picking the right primitive explicitly.

## Choosing a dispatcher in your own code

The module makes the choice injectable; it doesn't make it for you. The usual mapping:

| Work | Dispatcher |
|---|---|
| Disk, network, any blocking call | `dispatchers.io` |
| UI state updates that must run on the main thread | `dispatchers.main` |
| CPU-bound computation (parsing, sorting, image math) | `dispatchers.default` |

## Common mistakes

**Injecting `AppDispatchers` but still naming `Dispatchers` somewhere in the class.** One
`withContext(Dispatchers.IO)` left behind defeats the seam for that path, and the test will still
hit a real background thread. Grep for `Dispatchers.` when you introduce the interface.

**Constructing `DefaultAppDispatchers()` inside the class instead of taking it as a parameter.**

```kotlin
// Wrong — nothing can substitute this
class UserRepository {
    private val dispatchers = DefaultAppDispatchers()
}

// Right
class UserRepository(private val dispatchers: AppDispatchers)
```

A default parameter value is an acceptable middle ground for a class with many call sites:

```kotlin
class UserRepository(
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
)
```

**Expecting `dispatchers.main` to work in a plain unit test with `DefaultAppDispatchers`.**
`Dispatchers.Main` needs a main looper, which a JVM unit test doesn't have. That's precisely the
case `TestAppDispatchers` exists for — substituting it removes the need for
`Dispatchers.setMain()`/`resetMain()` boilerplate entirely.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — full public surface with contracts
