# Getting started

This walks through the first integration of KMPToolkit into a Kotlin Multiplatform project, from
zero to a compiling example. It does not cover any one module in depth — that's what each module's
own `docs/<module>/02-getting-started.md` is for.

## 1. Add the BOM

The BOM pins a matching version for every `kmptoolkit-*` artifact, so you never have to track
versions per module by hand:

```kotlin
// commonMain source set, or a shared dependencies block that all targets see
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
}
```

Replace `<version>` with the latest release — see the badge on the root
[`README.md`](../README.md) or [`CHANGELOG.md`](../CHANGELOG.md).

## 2. Add the module(s) you need

With the BOM in place, module coordinates don't need an explicit version:

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-logging")
}
```

Check the root README's module table for what each artifact depends on — a module with
dependencies (e.g. `kmptoolkit-permission` needs `kmptoolkit-storage`) pulls those in
transitively; you don't need to list them yourself.

## 3. A working example

`kmptoolkit-logging` is the smallest module — no other `kmptoolkit-*` module, no coroutines, no
third-party logger — and a good first integration check:

```kotlin
import io.github.jamal_wia.kmptoolkit.logging.LoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.i

class UserRepository(
    loggerFactory: LoggerFactory = createLoggerFactory(),
) {
    private val log = loggerFactory.logger("UserRepository")

    suspend fun loadUsers(): List<User> {
        log.i { "loading users" }
        return emptyList()
    }
}
```

If this compiles and resolves on both your Android and iOS source sets, the toolchain is wired up
correctly and you're ready to add other modules.

## 4. Where to go next

- [`01-architecture.md`](01-architecture.md) — read this before reaching for a second module; it
  explains conventions (no DI, config objects instead of hardcoded identifiers) that every module
  follows and that shape how you'll use them.
- [`02-platform-setup.md`](02-platform-setup.md) — if the module you're adding needs a platform
  permission or manifest entry.
- The module's own `docs/<module>/02-getting-started.md` for anything beyond `kmptoolkit-logging`.

## Troubleshooting a version that won't resolve

If a `kmptoolkit-*` dependency fails to resolve on one specific target (commonly iOS) even though
`commonMain` compiles fine, make sure `platform("io.github.jamal-wia:kmptoolkit-bom:<version>")` is
applied to that target's source set too, not only `commonMain` — this is a known Gradle/KMP
limitation, tracked upstream as
[KT-58759](https://youtrack.jetbrains.com/issue/KT-58759).
