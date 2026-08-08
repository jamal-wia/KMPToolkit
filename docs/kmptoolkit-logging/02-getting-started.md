# kmptoolkit-logging — Getting started

A minimal working example, start to finish.

## 1. Add the dependency

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-logging")
}
```

It brings nothing else with it — no other `kmptoolkit-*` module, no coroutines, no third-party
logger, no Compose. The Kotlin standard library is the whole transitive graph.

## 2. Create one factory at startup

```kotlin
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.platformLogSink

val loggerFactory: LoggerFactory = createLoggerFactory(
    minLevel = LogLevel.DEBUG,
    sinks = listOf(platformLogSink()),
)
```

Both parameters have defaults — `createLoggerFactory()` with no arguments is exactly the call above
— but spelling them out once, where your object graph is built, is what makes the release
configuration in [step 5](#5-turn-it-down-or-off-in-release) an obvious edit rather than a hunt.

This module ships no DI bindings by design; hold the factory however you already hold singletons
(see [`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).

## 3. Take a `Logger` where you need one

Ask the factory for one logger per class or feature and keep it in a property — a `Logger` is
immutable and thread-safe, so there is nothing to synchronize:

```kotlin
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.LoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e

class UserRepository(
    loggerFactory: LoggerFactory,
) {
    private val log: Logger = loggerFactory.logger("UserRepository")

    suspend fun loadUserName(id: String): String {
        log.d { "loading user $id" }
        return runCatching { fetch(id) }
            .onFailure { failure -> log.e(failure) { "load failed for user $id" } }
            .getOrThrow()
    }
}
```

`d`, `i`, `w`, `e` and `v` are extension functions, so each needs its own import — your IDE adds
them on completion.

## 4. Read the output

Android (logcat):

```
D/UserRepository: loading user 42
```

iOS (Xcode console):

```
D/UserRepository: loading user 42
```

See [`05-platform-notes.md`](05-platform-notes.md) for the exact formats and their limits.

## 5. Turn it down — or off — in release

Two levers, both at the factory:

```kotlin
// Release: warnings and errors only.
val loggerFactory: LoggerFactory = createLoggerFactory(minLevel = LogLevel.WARN)

// Release: nothing at all — no sinks means no message lambda is ever evaluated.
val loggerFactory: LoggerFactory = createLoggerFactory(sinks = emptyList())
```

Call sites do not change. A `log.d { buildExpensiveString() }` under either configuration never
runs its lambda.

## Expected result

Your shared code logs through one injected seam, and the app it ships in decides — per build type,
per platform — where those lines go and which of them are worth keeping.

## Read next

- [`03-guide.md`](03-guide.md) — writing sinks, bridging to Timber/Kermit/`os_log`, testing, pitfalls
- [`04-api-reference.md`](04-api-reference.md) — the full public surface
