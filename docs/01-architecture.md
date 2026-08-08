# Architecture

Cross-cutting principles every `kmptoolkit-*` module follows, and the reasoning behind each one.
Read this once, before your second module — it explains conventions you'll otherwise have to
rediscover module by module.

## No dependency-injection framework

No module depends on Koin, Hilt, Kodein, or any other DI library. A module's public API is an
interface plus a plain factory function:

```kotlin
val player: AudioPlayer = createAudioPlayer(config = AudioPlayerConfig(...))
```

**Why:** a library that hands you a `Module { }` block locks every consumer into that DI framework.
KMPToolkit modules are meant to drop into a Koin app, a Hilt app, a hand-wired app, or no DI at all
— you decide how to construct and hold the instance.

If you use Koin (or another framework), wrapping the factory in your own module definition is a
few lines:

```kotlin
val audioModule = module {
    single<AudioPlayer> { createAudioPlayer(config = get()) }
}
```

## Platform factories, not `expect fun`

A module whose implementation is platform-specific exposes a **common interface** plus a factory
declared separately in each platform source set — not a single `expect fun` with one shared
signature:

```kotlin
// commonMain — the type your shared code depends on
public interface HapticFeedback { public fun perform(type: HapticType): HapticResult }

// androidMain
public fun createHapticFeedback(context: Context): HapticFeedback
// iosMain
public fun createHapticFeedback(): HapticFeedback
```

**Why:** platforms genuinely need different things to construct the same abstraction — Android
needs a `Context`, iOS needs a bundle or a sound resolver or nothing at all. An `expect fun` forces
one signature on both, so every platform ends up declaring parameters it ignores, or the module
invents an `expect class PlatformContext` wrapper that exists purely to satisfy the shape. Both
make the API lie about what a platform actually requires.

Shared code never calls the factory: it takes the interface as a constructor parameter. Only the
platform entry point — `Application.onCreate`, an iOS app delegate — names the factory, and that
code is already platform-specific.

## Configuration instead of hardcoded identifiers

Nothing in the toolkit hardcodes a SharedPreferences name, a Keychain service string, a
notification channel id, or a background-task identifier. Every module that needs one of these
takes it through a config object in its constructor/factory, with a default derived from the
consumer's own package name where a sensible default exists:

```kotlin
data class StorageConfig(
    val preferencesName: String = "kmptoolkit_prefs",
    val keychainService: String? = null, // defaults to the app's bundle id on iOS
)
```

**Why:** a library that hardcodes its own identifier namespace collides the moment two
KMPToolkit-based libraries — or two versions of the same one — end up in the same process, and
makes it impossible for two features of the same app to keep separate storage. See the module's own
`docs/<module>/01-overview.md` for its specific config type and defaults.

## No user-facing text

Modules return typed state and typed errors, never a string meant for display. `AudioPlayer`
exposes `PlayerState.Error(cause: Throwable)`, not `"Playback failed"`. Translating and presenting
that state is the consuming app's responsibility — it already owns its localization pipeline and
copy tone; the toolkit would only get in the way.

## Public API and `@ToolkitInternalApi`

Every module builds with `explicitApi()` in strict mode: a symbol's visibility must be stated, not
inferred, and anything not meant for consumers is `internal`.

Some code needs to be visible **across** kmptoolkit modules without being part of the public
contract. That code is marked with an opt-in annotation:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Cross-module internal API — not part of the public contract.")
@Retention(AnnotationRetention.BINARY)
annotation class ToolkitInternalApi
```

If your code needs `@OptIn(ToolkitInternalApi::class)` to compile against a KMPToolkit module,
you're depending on an implementation detail that can change in any release without a major-version
bump.

## Test fixtures ship as separate `-testing` artifacts

Where a module offers a test double — `TestAppDispatchers`, an in-memory storage, a fake outbox —
that fixture lives in its own artifact (`kmptoolkit-coroutines-testing`), never in the production
module:

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-coroutines")
    testImplementation("io.github.jamal-wia:kmptoolkit-coroutines-testing")
}
```

**Why:** a fixture pulls in test infrastructure — `TestAppDispatchers` needs
`kotlinx-coroutines-test`. Shipping it inside the production module puts that test framework on the
**runtime classpath of every consuming app**, where it has no business being. Kotlin Multiplatform
has no way to expose one module's `commonTest` to a consumer, so a separate published artifact is
the only mechanism that keeps the production POM clean.

The cost is one extra artifact per module that has fixtures, and it is worth paying: a consumer who
never writes a test never downloads the fixture, and one who does gets it under
`testImplementation` where it belongs.

## ABI validation and semver

Every module's public ABI is dumped to `<module>/api/` and checked on every build (`checkKotlinAbi`,
wired by the `kmptoolkit.library` convention plugin — see `build-logic/`). A change to the dump
must be intentional (`./gradlew updateKotlinAbi`) and reviewed like any other change to public API.

Version policy:

- **Patch** — bug fixes, no public API change.
- **Minor** — additive public API only (new module, new function, new optional parameter with a
  default).
- **Major** — any breaking change to a published module's public API.

**Before `1.0.0`**, a breaking change is still possible in a minor bump but is called out explicitly
in `CHANGELOG.md` under its own `Breaking` heading — see `CHANGELOG.md`'s own header note. After
`1.0.0`, a breaking change requires a major version.

## Compose modules are opt-in artifacts

Only two modules depend on Compose Multiplatform: `kmptoolkit-systembars` and
`kmptoolkit-logging-overlay`. Every other module is plain Kotlin with no UI framework dependency —
adding, say, `kmptoolkit-outbox` to a non-Compose (or non-UI) target never pulls in Compose.

A Compose module also publishes a **smaller set of Apple targets** than the rest of the suite:
`iosArm64` and `iosSimulatorArm64` only, no `iosX64`, because Compose Multiplatform 1.11+ publishes
no iosX64 variant. That asymmetry is visible in the `.klib.api` dumps and is intentional.

### Notes for anyone adding a Compose module

Four things cost the first Compose module a build cycle each; they are recorded here so the next
one does not rediscover them:

- Robolectric Compose UI tests need `debugImplementation(platform(libs.androidx.compose.bom))` and
  `debugImplementation(libs.androidx.compose.ui.test.manifest)` in a plain `dependencies { }`
  block. It must be `debugImplementation` — an `androidUnitTest` dependency merges into the
  manifest too late, and the failure is an opaque *"Unable to resolve activity for Intent …
  ComponentActivity"*. The debug variant is never published, so nothing reaches consumers.
- Those tests also need `@Config(sdk = [34])`. The default is `compileSdk` 37, which no released
  Robolectric emulates, and the failure names no SDK level.
- `compose.uiTest` requires `@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)`
  at the top of the build file.
- `androidx.compose.ui.test.runComposeUiTest` is deprecated in 1.11; import
  `androidx.compose.ui.test.v2.runComposeUiTest` instead — same signature.

## Android manifests

Library modules do not declare Android permissions in their own `AndroidManifest.xml`, even when
the feature they wrap needs one (`kmptoolkit-scheduler` and `SCHEDULE_EXACT_ALARM` is the clearest
example). A manifest-merged permission would appear in every consumer's app silently, which is not
a decision a library gets to make for its consumer — especially one that requires justification in
a Play Store listing. Each module's `docs/<module>/05-platform-notes.md` states exactly which
permission the consumer must declare, and documents the module's fallback behavior when that
permission is missing or denied.
