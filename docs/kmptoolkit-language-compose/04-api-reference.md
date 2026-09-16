# kmptoolkit-language-compose — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.language.compose`.

### `AppLocale`

```kotlin
@Composable
public fun AppLocale(
    language: AppLanguage,
    resolvedLanguage: AppLanguage = language,
    content: @Composable () -> Unit,
)
```

Makes `content` render in `language`: its reading direction as `LocalLayoutDirection`, plus whatever
else the platform needs for a string resource to resolve under it.

| Parameter | Contract |
|---|---|
| `language` | The selection itself, `AppLanguage.System` included. This is what the platform locale is pinned to |
| `resolvedLanguage` | The same selection with `System` already resolved — `catalog.resolve(language)`. Only `isLtr` is read from it. Defaults to `language`, which is correct whenever you already hold a resolved one |

Passing `System` as `language` is meaningful rather than a mistake: it pins the *device's* language,
which is not the same as pinning the language that device happens to be set to today. Pin the latter
and a device language change stops reaching the app.

What "whatever else the platform needs" means differs by platform, and the difference is visible —
on Android a language change keeps the subtree's remembered state, on iOS it does not. See
[`05-platform-notes.md`](05-platform-notes.md).

### `Modifier.mirrorOnRtl`

```kotlin
@Composable
@ReadOnlyComposable
public fun Modifier.mirrorOnRtl(): Modifier
```

Appends a horizontal flip (`Modifier.scale(scaleX = -1f, scaleY = 1f)`) when `LocalLayoutDirection`
is `Rtl`; returns the receiver unchanged under `Ltr`.

### `Modifier.mirrorOnLtr`

```kotlin
@Composable
@ReadOnlyComposable
public fun Modifier.mirrorOnLtr(): Modifier
```

The mirror image of `mirrorOnRtl`: flips under `Ltr`, returns the receiver unchanged under `Rtl`.

## Testing fixtures

None. All three symbols are pure functions of `LocalLayoutDirection` / their `AppLanguage` argument
— exercise them directly with `CompositionLocalProvider(LocalLayoutDirection provides ...)` in a
Compose UI test, as this module's own tests do, rather than through a fixture.
