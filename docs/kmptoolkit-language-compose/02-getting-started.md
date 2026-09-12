# kmptoolkit-language-compose — Getting started

## 1. Add the dependency

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-language")
            implementation("io.github.jamal-wia:kmptoolkit-language-compose")
        }
    }
}
```

## 2. Wrap your app root

Resolve `AppLanguage.System` to a real language first — see
[`kmptoolkit-language`'s guide](../kmptoolkit-language/03-guide.md#resolving-follow-system) — then
wrap the whole tree, above anything that reads `LocalLayoutDirection` or shows localized text:

```kotlin
@Composable
fun App(languageHolder: AppLanguageHolder) {
    val selected: AppLanguage by languageHolder.languageFlow.collectAsState()

    AppLocale(language = resolve(selected, SupportedLanguage.entries)) {
        MaterialTheme {
            // the rest of your app
        }
    }
}
```

Picking a language elsewhere in the app — `languageHolder.setLanguage(...)` — recomposes this from
the top, in the new direction, with fresh string lookups.

## 3. Mirror a directional drawable

```kotlin
Icon(
    imageVector = Icons.Default.ChevronRight,
    contentDescription = null,
    modifier = Modifier.mirrorOnRtl(),
)
```

Prefer `Icons.AutoMirrored.*` when Compose already ships an auto-mirrored version of the icon you
need — reach for `mirrorOnRtl` / `mirrorOnLtr` only for a project-owned vector asset.

## Next

[`03-guide.md`](03-guide.md) — what already auto-mirrors without either modifier, and the one case
(media transport controls) where mirroring is a product decision, not an automatic one.
