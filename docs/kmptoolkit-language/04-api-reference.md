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

No display name, no flag, no language list. See [`01-overview.md`](01-overview.md#what-this-module-deliberately-does-not-carry).

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
| `setLanguage(language)` | Updates `languageFlow`, calls [`applyLanguageGlobally`](#applylanguageglobally-and-getsystemlanguagecode), then the `onLanguageChanged` callback given to [`createAppLanguageHolder`](#createapplanguageholder) — unless `language` already equals the current one, in which case none of that happens |

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
