# kmptoolkit-settings — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.settings`. Contracts here are the ones the
tests assert; where a doc comment and this page disagree, both are wrong and it is a bug.

## `AppSettings`

```kotlin
public interface AppSettings {
    public val fontScale: StateFlow<FontScale>
    public val themeMode: StateFlow<ThemeMode>
    public val language: StateFlow<LanguageTag?>

    public fun setFontScale(value: FontScale): SettingsResult
    public fun setThemeMode(value: ThemeMode): SettingsResult
    public fun setLanguage(value: LanguageTag?): SettingsResult
}
```

**Reading.** Each flow starts at the loaded value and changes only through this instance's own
setters. It is not a live view of the store: a second `AppSettings` over the same storage, or
another process, does not push here.

**Writing.** Each setter persists first and publishes second:

| Outcome | Store | Flow | Return |
|---|---|---|---|
| Value already current | untouched | untouched | `Success` |
| Write succeeded | new value | new value | `Success` |
| Write rejected by the store | untouched | **untouched** | `Failure(WriteFailed(key, cause))` |
| Language outside `supportedLanguages` | untouched | untouched | `Failure(UnsupportedLanguage(tag))` |

`setLanguage(null)` means "follow the operating system" and is always accepted, whatever
`supportedLanguages` holds. It persists the empty string rather than removing the key, so that
"the user chose System" stays distinguishable from "the user never chose".

**Nothing suspends.** Persisting goes through `KeyValueStorage`, whose writes are in-memory
commits the platform flushes (`SharedPreferences.apply`, `NSUserDefaults`). That is not the
filesystem work that makes a suspending signature worth its cost — the rule this module follows is
the same one `kmptoolkit-audio-recorder` states: an operation that can genuinely block suspends,
one that moves a value does not.

**Threading.** Flows are readable from anywhere. Every individual write lands whole or fails
whole. Two concurrent writes to *different* settings never interfere. Two concurrent writes to the
*same* setting race, and because persisting and publishing are two steps, the flow and the store
can end on different ones of the two written values — the disagreement then surfaces at the next
launch. Drive writes to one setting from a single dispatcher if that matters.

## `createAppSettings` and `SettingsLoad`

```kotlin
public fun createAppSettings(
    storage: KeyValueStorage,
    config: SettingsConfig = SettingsConfig(),
): SettingsLoad

public data class SettingsLoad(
    public val settings: AppSettings,
    public val problems: List<SettingsError>,
)
```

Reads the three keys, in the order font scale → theme mode → language, and always produces a
usable `AppSettings`. Each setting that could not be loaded contributes exactly one entry to
`problems`, in that same order:

| Stored | Loaded as | Problem |
|---|---|---|
| nothing | the configured default | — |
| a valid value | that value | — |
| something unparsable, or a font scale out of range | the configured default | `UnreadableValue(key, raw)` |
| a language tag not in `supportedLanguages` | `defaultLanguage` | `UnsupportedLanguage(tag)` |
| unreadable — the store failed | the configured default | `ReadFailed(key, cause)` |

Loading never writes: a corrupted entry is left for the next successful write to overwrite, so a
value that is merely unreadable *today* is not destroyed.

Construct one per app at start-up and hold it. It owns no native handle, registers nothing, and
needs no release.

## `FontScale`

```kotlin
@JvmInline public value class FontScale(public val multiplier: Float) {
    public companion object {
        public const val MINIMUM_MULTIPLIER: Float // 0.5
        public const val MAXIMUM_MULTIPLIER: Float // 3.0
        public val DEFAULT: FontScale             // 1.0
        public fun of(multiplier: Float): FontScale?
        public fun coerced(multiplier: Float): FontScale
    }
}
```

- The constructor throws `IllegalArgumentException` outside `0.5..3.0`, and for `NaN` and both
  infinities. Use it for literals.
- `of` returns `null` for the same inputs. Use it for values that come from data.
- `coerced` clamps to the nearer end; `NaN` becomes `DEFAULT`, since it names no direction.
- Persisted as the multiplier's decimal string (`"1.15"`).

There are no named steps — see [`03-guide.md`](03-guide.md#defining-font-scale-steps).

## `ThemeMode`

```kotlin
public enum class ThemeMode { SYSTEM, LIGHT, DARK }
```

Persisted as the constant's name (`"DARK"`), matched case-sensitively on the way back in.
`SYSTEM` is deliberately not resolved into a concrete scheme by this module.

## `LanguageTag`

```kotlin
@JvmInline public value class LanguageTag private constructor(public val value: String) {
    public val language: String
    public companion object {
        public operator fun invoke(tag: String): LanguageTag
        public fun ofOrNull(tag: String): LanguageTag?
    }
}
```

- `LanguageTag("pt-BR")` reads like a constructor and throws on a malformed tag; `ofOrNull`
  returns `null` instead.
- **Canonicalised on construction**: language lowercase, four-letter script Titlecase, two-letter
  region UPPERCASE. `LanguageTag("PT-br") == LanguageTag("pt-BR")`.
- **Syntax only.** A primary subtag of 2–8 ASCII letters, then subtags of 1–8 ASCII letters or
  digits, `-` separated. `"en_US"`, `"en-"`, `"English (US)"` and non-ASCII text are rejected;
  `"english"` is accepted, because syntax is all this checks. Whether your app can render a tag is
  `SettingsConfig.supportedLanguages`' question.
- `language` is the primary subtag, for a "do I have anything in this user's language" match.
- `toString()` is `value`.

## `SettingsConfig`

```kotlin
public data class SettingsConfig(
    public val keyPrefix: String = DEFAULT_KEY_PREFIX,
    public val supportedLanguages: Set<LanguageTag> = emptySet(),
    public val defaultFontScale: FontScale = FontScale.DEFAULT,
    public val defaultThemeMode: ThemeMode = ThemeMode.SYSTEM,
    public val defaultLanguage: LanguageTag? = null,
) {
    public val fontScaleKey: String  // "$keyPrefix.font_scale"
    public val themeModeKey: String  // "$keyPrefix.theme_mode"
    public val languageKey: String   // "$keyPrefix.language"
}
```

`DEFAULT_KEY_PREFIX` is `"io.github.jamal_wia.kmptoolkit.settings"` — this module's own package,
a namespace nothing else writes to. It is not the consuming app's identifier because the store is
already scoped to the app by `StorageConfig`.

Throws `IllegalArgumentException` for a blank `keyPrefix`, and for a `defaultLanguage` that is not
in a non-empty `supportedLanguages` — a fallback the app cannot render is not a fallback.

An empty `supportedLanguages` accepts every well-formed tag.

## `SettingsResult` and `SettingsError`

```kotlin
public sealed interface SettingsResult {
    public data object Success : SettingsResult
    public data class Failure(public val error: SettingsError) : SettingsResult
}

public fun SettingsResult.errorOrNull(): SettingsError?
public val SettingsResult.isSuccess: Boolean

public sealed interface SettingsError {
    public data class ReadFailed(val key: String, val cause: StorageError) : SettingsError
    public data class WriteFailed(val key: String, val cause: StorageError) : SettingsError
    public data class UnreadableValue(val key: String, val rawValue: String) : SettingsError
    public data class UnsupportedLanguage(val tag: LanguageTag) : SettingsError
}
```

`ReadFailed` appears only in `SettingsLoad.problems`; the other three can appear in either place.
None of them is a message — see [`01-architecture.md`](../01-architecture.md) on why no module
returns display text.

## `LanguageApplier`

```kotlin
public fun interface LanguageApplier {
    public fun apply(language: LanguageTag?)
}

// androidMain
public fun createLanguageApplier(context: Context): LanguageApplier
// iosMain
public fun createLanguageApplier(): LanguageApplier
```

Applies the language process-wide, or hands the choice back to the OS when `null`. Idempotent,
never throws, returns nothing — none of the three platform mechanisms reports whether the language
was accepted, so a result here would be an invented `Success`.

The Android factory takes a `Context` and the iOS one takes nothing: that asymmetry is exactly why
this is an interface with per-platform factories rather than one shared `expect fun`. Only the
application context is retained.

**What "applies" means, and when it becomes visible, differs per platform** —
[`05-platform-notes.md`](05-platform-notes.md) is required reading before you build the UI around
it.
