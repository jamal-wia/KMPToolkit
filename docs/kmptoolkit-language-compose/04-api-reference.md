# kmptoolkit-language-compose — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.language.compose`.

### `AppLocale`

```kotlin
@Composable
public fun AppLocale(language: AppLanguage, content: @Composable () -> Unit)
```

Provides `language`'s reading direction as `LocalLayoutDirection` for `content`, and forces a fresh
composition of `content` whenever `language.code` changes (`key(language.code) { ... }`).

Pass an already-resolved `language` — one whose `code` is never `null`. `AppLanguage.System.isLtr` is
a placeholder; resolve it against your own supported-language list first — see
[`kmptoolkit-language`'s guide](../kmptoolkit-language/03-guide.md#resolving-follow-system).

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
