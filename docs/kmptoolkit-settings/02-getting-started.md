# kmptoolkit-settings — getting started

Five minutes to a font scale, a theme mode and a language that survive a restart.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-settings")
            // The store the settings are kept in.
            implementation("io.github.jamal-wia:kmptoolkit-storage")
        }
    }
}
```

No permission, no manifest entry, no `Info.plist` key — see
[`05-platform-notes.md`](05-platform-notes.md).

## 2. Create it once, at start-up

`createAppSettings` reads the three values out of the store and hands you an `AppSettings` plus
whatever could not be read. Do it once and hold the result for the app's lifetime — every later
read is then a flow lookup rather than a store hit.

```kotlin
import io.github.jamal_wia.kmptoolkit.settings.*
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage

class AppContainer(storage: KeyValueStorage) {

    val settings: AppSettings

    init {
        val (settings, problems) = createAppSettings(
            storage = storage,
            config = SettingsConfig(
                supportedLanguages = setOf(LanguageTag("en"), LanguageTag("pt-BR")),
            ),
        )
        this.settings = settings
        problems.forEach { println("settings did not load: $it") }
    }
}
```

`problems` is empty on a fresh install and on every launch that loads cleanly. It is not decorative
— see [`03-guide.md`](03-guide.md#what-to-do-with-a-load-problem).

The `storage` argument is any `KeyValueStorage`; the plain one is right, these are preferences
rather than secrets:

```kotlin
// Android — from Application.onCreate
val storage: KeyValueStorage = createKeyValueStorage(context)
// iOS — from your app delegate or a Koin/manual composition root
let storage = createKeyValueStorage()
```

## 3. Read the values in your UI

Each setting is a `StateFlow`, so it drops straight into Compose:

```kotlin
@Composable
fun App(settings: AppSettings) {
    val themeMode: ThemeMode by settings.themeMode.collectAsStateWithLifecycle()
    val fontScale: FontScale by settings.fontScale.collectAsStateWithLifecycle()

    val dark: Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val density: Density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale.multiplier),
        ) {
            Content()
        }
    }
}
```

Scaling `LocalDensity.fontScale` rather than only your text styles is what makes icons and touch
targets grow with the text, which is the difference between an accessibility setting and one that
fails an audit.

## 4. Write a value

```kotlin
when (val result: SettingsResult = settings.setThemeMode(ThemeMode.DARK)) {
    SettingsResult.Success -> Unit
    is SettingsResult.Failure -> showToast("Could not save that — ${result.error}")
}
```

A failed write leaves both the store and the flow on the previous value, so the UI stays truthful:
the toggle you see is the toggle that will be there after a restart.

## 5. Apply the language

Storing a language does not change what the app renders in — hand it to the platform with a
`LanguageApplier`:

```kotlin
// Android, from Application.onCreate — a Context is needed here, none is on iOS
val applier: LanguageApplier = createLanguageApplier(context)
// iOS
let applier = createLanguageApplier()

// Shared: apply what was loaded, then everything the user picks afterwards.
scope.launch { settings.language.collect(applier::apply) }
```

**When that becomes visible differs sharply per platform** — Android 13+ recreates your activities,
older Android applies it to resources loaded afterwards, and iOS usually needs a restart. Read
[`05-platform-notes.md`](05-platform-notes.md) before you design the "language changed" moment in
your UI.

Next: [`03-guide.md`](03-guide.md) for font-scale steps, language display names, load problems and
testing.
