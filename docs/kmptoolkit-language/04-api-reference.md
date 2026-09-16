# kmptoolkit-language — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.language`.

## `AppLanguage`

```kotlin
public data class AppLanguage(
    val code: String?,
    val isLtr: Boolean,
) {
    public companion object {
        public val System: AppLanguage
    }
}
```

| Member | Contract |
|---|---|
| `code` | BCP-47 / ISO language tag (`"en"`, `"ar"`, `"pt-BR"`, …), or `null` for [`System`](#system) |
| `isLtr` | `false` only for a right-to-left language. Meaningless when `code` is `null` |
| `System` | Follows the device's own language. `isLtr` on this instance is a placeholder (`true`), never meant to be read — see [`03-guide.md`](03-guide.md#resolving-follow-system) |

No display name, no flag, no fixed language list. See [`01-overview.md`](01-overview.md#what-this-module-deliberately-does-not-carry).

## `AppLanguageCatalog`

```kotlin
public interface AppLanguageCatalog {
    public val supported: List<AppLanguage>
    public val fallback: AppLanguage
    public val systemId: String
    public fun byCode(code: String): AppLanguage?
    public fun idOf(language: AppLanguage): String
    public fun fromId(id: String?): AppLanguage
    public fun resolveSystemLanguage(): AppLanguage
    public fun resolve(language: AppLanguage): AppLanguage
}
```

| Member | Contract |
|---|---|
| `supported` | The languages you passed, in the order you passed them. Never contains `System` |
| `fallback` | Served to a device whose language you do not offer. Always an element of `supported` |
| `systemId` | The persisted identity of `AppLanguage.System` |
| `byCode(code)` | The supported language with that code, or `null`. Case-insensitive, and applies the JDK's legacy code remaps (`in`→`id`, `iw`→`he`, `ji`→`yi`) |
| `idOf(language)` | The string to persist: the language's `code`, or `systemId` when that is `null` |
| `fromId(id)` | The language a persisted id denotes. An unknown or `null` id resolves to `System` rather than failing |
| `resolveSystemLanguage()` | The supported language best matching the device right now — whole tag, then primary subtag (`pt-BR` → `pt`), then `fallback`. Re-read on every call, never cached |
| `resolve(language)` | `language` itself, or `resolveSystemLanguage()` when its `code` is `null`. A language outside `supported` is returned unchanged |

### `createAppLanguageCatalog`

```kotlin
public fun createAppLanguageCatalog(
    supported: List<AppLanguage>,
    fallback: AppLanguage = supported.first(),
    systemId: String = "system",
    systemLanguageCode: () -> String? = ::getSystemLanguageCode,
): AppLanguageCatalog
```

Throws `IllegalArgumentException` if `supported` is empty, contains `AppLanguage.System`, or repeats
a code; if `fallback` is not in `supported`; or if `systemId` collides with a supported code.

`systemId` ends up in *your* storage, so it is a parameter rather than a constant, like every other
consumer-facing identifier in this suite. Change it only to match values an existing app already has
on disk, and never after shipping — see
[`03-guide.md`](03-guide.md#migrating-an-app-that-already-persists-a-language).

`systemLanguageCode` is a test seam. Production code should leave the default.

## `AppLanguageHolder`

```kotlin
public interface AppLanguageHolder {
    public val languageFlow: StateFlow<AppLanguage>
    public val language: AppLanguage                       // = languageFlow.value
    public fun setLanguage(language: AppLanguage)
}
```

| Member | Contract |
|---|---|
| `languageFlow` | The currently selected language. Distinct values only |
| `language` | Snapshot of the above |
| `setLanguage(language)` | Calls [`applyLanguageGlobally`](#applylanguageglobally-and-getsystemlanguagecode) **on every call**, including one passing the language already in effect — the platform default is shared state the OS itself rewrites, so re-asserting it is the point. Then, only when the language actually changed, updates `languageFlow` and calls the `onLanguageChanged` callback given to [`createAppLanguageHolder`](#createapplanguageholder) |

### `createAppLanguageHolder`

```kotlin
public fun createAppLanguageHolder(
    initialLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit = {},
    applyGlobally: (AppLanguage) -> Unit = ::applyLanguageGlobally,
): AppLanguageHolder
```

Common to both platforms — no `Context` needed. Calls `applyGlobally(initialLanguage)` before
returning. `onLanguageChanged` is not called for `initialLanguage` itself, only for a later
`setLanguage` that actually changes something. `applyGlobally` defaults to the real
[`applyLanguageGlobally`](#applylanguageglobally-and-getsystemlanguagecode); override it only in a
test that wants a holder without touching the real platform locale — production code should leave
the default.

## `applyLanguageGlobally` and `getSystemLanguageCode`

```kotlin
public expect fun applyLanguageGlobally(language: AppLanguage)
public expect fun getSystemLanguageCode(): String?
```

| Function | Contract |
|---|---|
| `applyLanguageGlobally(language)` | Sets the platform's default locale. Called automatically by `AppLanguageHolder` — you should not normally call it yourself. `AppLanguage.System` clears a previous override rather than leaving it in place |
| `getSystemLanguageCode()` | The device's own preferred language, independent of any prior `applyLanguageGlobally` call. `null` if the platform reports none |

See [`05-platform-notes.md`](05-platform-notes.md) for exactly what each does on Android and iOS.

## Android-only: `localizedContext`

```kotlin
public fun localizedContext(base: Context, language: AppLanguage): Context
```

Wraps `base` with a configuration override carrying only `language`'s locale — for
`Activity.attachBaseContext`, so the very first frame after a (re)creation resolves resources in the
chosen language rather than the device's. Returns `base` unchanged for `AppLanguage.System`. See
[`05-platform-notes.md`](05-platform-notes.md#android).

## Android-only: `LocalizedApplicationResources`

```kotlin
public class LocalizedApplicationResources {
    public fun readLanguageFrom(source: () -> AppLanguage)
    public fun resourcesOf(base: Context): Resources
}
```

Serves your `Application`'s resources under the chosen language. Install it by overriding
`Application.getResources()` and pointing `readLanguageFrom` at your holder once it exists.

| Member | Contract |
|---|---|
| `readLanguageFrom(source)` | Tells it where to read the current language. Not a subscription: `source` is asked again on every `resourcesOf` call, so a change takes effect on the next lookup and `System` keeps following the device. Two-phase because the `Application` field exists before the persisted language is readable |
| `resourcesOf(base)` | The resources to serve for `base` — your `Application`'s own base context. Returns `base.resources` untouched until `readLanguageFrom` has run. Safe from any thread: a race at worst builds equivalent resources twice |

This is the piece an app is most likely to skip and most likely to regret — see
[`05-platform-notes.md`](05-platform-notes.md#android) for what breaks without it.

## Testing fixtures

None. `AppLanguageHolder` is a three-member interface with no platform type in its signature — a
fake is shorter than the import that would bring one in:

```kotlin
class FakeAppLanguageHolder(initial: AppLanguage) : AppLanguageHolder {
    private val state = MutableStateFlow(initial)
    override val languageFlow: StateFlow<AppLanguage> = state
    override fun setLanguage(language: AppLanguage) { state.value = language }
}
```

Unlike the real holder, a fake used in a test should **not** call `applyLanguageGlobally` — the real
holder's global side effect is exactly what a unit test wants to avoid touching.
