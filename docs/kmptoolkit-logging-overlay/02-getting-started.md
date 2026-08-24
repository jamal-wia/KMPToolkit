# kmptoolkit-logging-overlay — Getting started

Five minutes from nothing to logs on the device's screen. Read
[`01-overview.md`](01-overview.md) first if you have not — in particular the part about never
shipping this in a release build.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared/Compose module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-logging-overlay")
        }
    }
}
```

`kmptoolkit-logging` comes with it as an `api` dependency — you do not add it separately, and you
can use `Logger`, `LogLevel` and `LogSink` directly.

**Targets:** Android, `iosArm64`, `iosSimulatorArm64` — the same set as every other module in the
suite. There is no `iosX64` variant. If you still build for the Intel simulator, this module cannot
be part of that build.

## 2. Create the state once, at app start

```kotlin
// Something that lives as long as your app does — an application-scope object, a DI graph, a
// top-level val in your app module. Not `remember`.
val overlayState = LogOverlayState()
```

## 3. Install its sink into your logger factory

```kotlin
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.platformLogSink

val loggerFactory: LoggerFactory = createLoggerFactory(
    minLevel = LogLevel.DEBUG,
    sinks = if (isDebugBuild) {
        listOf(platformLogSink(), overlayState.asLogSink())
    } else {
        listOf(platformLogSink())
    },
)
```

`isDebugBuild` is yours: `BuildConfig.DEBUG` on Android, a Swift compilation condition passed into
Kotlin on iOS, a Gradle-generated constant — whatever your project already uses. This module does
not detect it for you, on purpose.

Nothing at a call site changes. Code that already does this keeps doing it:

```kotlin
class SyncEngine(loggerFactory: LoggerFactory) {
    private val log: Logger = loggerFactory.logger("Sync")

    fun sync() {
        log.i { "sync started" }
    }
}
```

## 4. Wrap your UI

```kotlin
@Composable
fun App() {
    MaterialTheme {
        LogOverlayHost(state = overlayState) {
            AppNavigation()
        }
    }
}
```

Inside `MaterialTheme`, not outside: the panel draws with your theme's colors and typography.

In a release build, skip the wrapper entirely — `if (isDebugBuild) LogOverlayHost(state) { App() } else App()`.

## 5. Give yourself a way to open it

The panel starts hidden and this module ships no trigger. Wire `show()` / `toggle()` to whatever
developer entry point you already have:

```kotlin
// A dev-menu row, a debug-only button, a triple-tap on the version label — your choice.
DevMenuItem(text = "Show logs", onClick = overlayState::show)
```

If you have nothing yet, a debug-only floating button is enough to start:

```kotlin
LogOverlayHost(state = overlayState) {
    Box {
        AppNavigation()
        if (isDebugBuild) {
            FloatingActionButton(
                onClick = overlayState::toggle,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text("L") }
        }
    }
}
```

## 6. Run it

Log something, open the panel. You get the newest record first, each row showing `[tag] message`
with its level and how long after app start it happened. Tap a row that carries a `Throwable` to
expand its stack trace; tap again to collapse. `Clear` empties the buffer, `Close` hides the panel.

## Where to go next

- Show only warnings and errors on screen while logcat still gets everything, or resize the buffer:
  [`03-guide.md`](03-guide.md).
- Replace the four English labels: [`03-guide.md`](03-guide.md#relabeling-the-chrome).
- Exact contracts, thread-safety, eviction order: [`04-api-reference.md`](04-api-reference.md).
