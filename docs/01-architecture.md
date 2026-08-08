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

Some code needs to be visible **across** kmptoolkit modules without being part of the public API —
test fixtures shipped in `commonMain` (see `docs/<module>/06-testing.md` where applicable) are the
main example, since Kotlin Multiplatform has no mechanism to share one module's `commonTest` with
another module. That code is marked with an opt-in annotation:

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Cross-module internal API — not part of the public contract.")
@Retention(AnnotationRetention.BINARY)
annotation class ToolkitInternalApi
```

If your code needs `@OptIn(ToolkitInternalApi::class)` to compile against a KMPToolkit module,
you're depending on an implementation detail that can change in any release without a major-version
bump.

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

## Android manifests

Library modules do not declare Android permissions in their own `AndroidManifest.xml`, even when
the feature they wrap needs one (`kmptoolkit-scheduler` and `SCHEDULE_EXACT_ALARM` is the clearest
example). A manifest-merged permission would appear in every consumer's app silently, which is not
a decision a library gets to make for its consumer — especially one that requires justification in
a Play Store listing. Each module's `docs/<module>/05-platform-notes.md` states exactly which
permission the consumer must declare, and documents the module's fallback behavior when that
permission is missing or denied.
