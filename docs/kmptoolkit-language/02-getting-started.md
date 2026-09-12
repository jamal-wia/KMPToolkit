# kmptoolkit-language — Getting started

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-language")
        }
    }
}
```

Plain Kotlin, no Compose dependency. Published for `android`, `iosArm64` and `iosSimulatorArm64`.

## 2. Define the languages your app offers

There is no catalog to extend — build your own list, with your own labels:

```kotlin
enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
    System(AppLanguage.System, "System"),
    English(AppLanguage(code = "en", isLtr = true), "English"),
    Arabic(AppLanguage(code = "ar", isLtr = false), "العربية"),
}
```

## 3. Create the holder

One per process, created wherever your app already assembles its long-lived objects. Load whatever
you last persisted before calling this — the module has no storage of its own:

```kotlin
class MyApplication : Application() {

    lateinit var language: AppLanguageHolder
        private set

    override fun onCreate() {
        super.onCreate()
        val saved: AppLanguage = loadSavedLanguage() ?: AppLanguage.System
        language = createAppLanguageHolder(
            initialLanguage = saved,
            onLanguageChanged = { persistLanguage(it) },
        )
    }
}
```

`createAppLanguageHolder` applies `initialLanguage` to the platform's default locale immediately, so
string-resource lookups anywhere in the process are correct from the first line after this call.

## 4. Let a screen change it

```kotlin
fun onLanguagePicked(picked: SupportedLanguage) {
    language.setLanguage(picked.appLanguage)
}
```

`setLanguage` updates `languageFlow`, re-applies the platform locale, and calls
`onLanguageChanged` — unless the picked language is already the one in effect, in which case it does
nothing at all.

## 5. If you support "follow system"

`AppLanguage.System` has no fixed `isLtr` of its own. Resolve it against your own
list when you need the actual language — for picking a translation, for instance:

```kotlin
fun resolve(selected: AppLanguage, supported: List<SupportedLanguage>): AppLanguage {
    if (selected != AppLanguage.System) return selected
    val deviceCode: String? = getSystemLanguageCode()
    return supported.firstOrNull { it.appLanguage.code == deviceCode }?.appLanguage
        ?: supported.first().appLanguage // your own fallback language
}
```

## 6. Compose apps: wire layout direction

```kotlin
implementation("io.github.jamal-wia:kmptoolkit-language-compose")
```

```kotlin
@Composable
fun App(language: AppLanguage) {
    AppLocale(language = resolve(language, SupportedLanguage.entries)) {
        // the rest of your Compose tree
    }
}
```

## Next

[`03-guide.md`](03-guide.md) — persistence patterns, the "follow system" resolution step in more
depth, and pitfalls worth knowing before you ship a language picker.
