# kmptoolkit-coroutines — API reference

Package: `io.github.jamal_wia.kmptoolkit.coroutines`

This file mirrors the committed ABI dump at `kmptoolkit-coroutines/api/`. If they disagree, the dump
is authoritative and this file is a bug.

## `interface AppDispatchers`

The dispatcher seam. Depend on this type; never on `Dispatchers` directly.

| Member | Type | Contract |
|---|---|---|
| `io` | `CoroutineDispatcher` | For blocking work — disk, network, any call that parks a thread |
| `main` | `CoroutineDispatcher` | For work that must run on the platform's main/UI thread |
| `default` | `CoroutineDispatcher` | For CPU-bound work |

All three are read-only properties. Implementations are expected to return the *same* dispatcher
instance on every access — callers may compare them by identity (`TestAppDispatchers` relies on
this, and `AppDispatchersTest` pins it).

Thread-safe: an implementation exposes immutable state and may be shared freely across coroutines.

## `class DefaultAppDispatchers : AppDispatchers`

The production implementation.

| Member | Value |
|---|---|
| `io` | `Dispatchers.IO` |
| `main` | `Dispatchers.Main` |
| `default` | `Dispatchers.Default` |

**Constructor:** `DefaultAppDispatchers()` — no arguments.

Constructing it is cheap (it only holds references to the standard dispatchers), so a single shared
instance and per-call-site instances are equally valid; there is no hidden state to share.

Note that `Dispatchers.Main` requires a platform main dispatcher to exist. On a plain JVM unit test
there is none, and *touching* the `main` property is what throws — so a test that exercises code
using `dispatchers.main` must substitute `TestAppDispatchers`.

## `class TestAppDispatchers : AppDispatchers`

The test double. All three dispatchers return the same `testDispatcher`.

**Constructor:**

```kotlin
TestAppDispatchers(scheduler: TestCoroutineScheduler = TestCoroutineScheduler())
```

| Parameter | Default | Meaning |
|---|---|---|
| `scheduler` | a fresh `TestCoroutineScheduler` | The virtual clock. Pass one explicitly to share a clock across several collaborators — see [`03-guide.md`](03-guide.md#sharing-one-scheduler-across-the-test) |

| Member | Type | Contract |
|---|---|---|
| `testDispatcher` | `CoroutineDispatcher` | An `UnconfinedTestDispatcher` over `scheduler`. Also returned by `io`, `main`, and `default` — identical instance |

`UnconfinedTestDispatcher` semantics apply: coroutines start eagerly, running to their first real
suspension point before `launch`/`async` returns.

This type ships in the module's **main** source set rather than a test source set, so it is
available to consumers' test code through the ordinary `implementation` dependency. Kotlin
Multiplatform provides no way to expose one module's `commonTest` to another module — see
[`../01-architecture.md`](../01-architecture.md#public-api-and-toolkitinternalapi).
