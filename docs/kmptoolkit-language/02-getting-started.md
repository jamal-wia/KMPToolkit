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

There is no catalog to extend — declare your own list, with your own labels:

```kotlin
enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
    English(AppLanguage(code = "en", isLtr = true), "English"),
    Arabic(AppLanguage(code = "ar", isLtr = false), "العربية"),
    Portuguese(AppLanguage(code = "pt", isLtr = true), "Português"),
}
```

`AppLanguage.System` is deliberately **not** in this list. It is a mode — "follow the device" — not a
language, and the catalog below is what turns it into one.

## 3. Build the catalog

```kotlin
val catalog: AppLanguageCatalog = createAppLanguageCatalog(
    supported = SupportedLanguage.entries.map { it.appLanguage },
)
```

That is enough for most apps: the first entry becomes the fallback for a device whose language you do
not offer, and `"system"` becomes the string stored for "follow the device". Both are parameters if
you need different ones — see [`03-guide.md`](03-guide.md#migrating-an-app-that-already-persists-a-language)
if your app already has values on disk.

## 4. Create the holder

One per process, created wherever your app already assembles its long-lived objects. The module has
no storage of its own, so read your stored value and hand it over:

```kotlin
class MyApplication : Application() {

    lateinit var language: AppLanguageHolder
        private set

    override fun onCreate() {
        super.onCreate()
        language = createAppLanguageHolder(
            initialLanguage = catalog.fromId(storage.getString(LANGUAGE_KEY)),
            onLanguageChanged = { storage.putString(LANGUAGE_KEY, catalog.idOf(it)) },
        )
    }
}
```

`createAppLanguageHolder` applies `initialLanguage` to the platform's default locale immediately, so
string-resource lookups anywhere in the process are correct from the first line after this call.

## 5. Android: carry the language on the Application and the Activity

Two overrides, and the language then survives everything the OS does to your process.

```kotlin
class MyApplication : Application() {

    private val localizedResources = LocalizedApplicationResources()

    override fun getResources(): Resources = localizedResources.resourcesOf(baseContext)

    override fun onCreate() {
        super.onCreate()
        // …create the holder as above, then:
        localizedResources.readLanguageFrom { language.language }
    }
}
```

```kotlin
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, currentLanguage()))
    }
}
```

Neither is optional, and they cover different failures: `attachBaseContext` makes the first frame
after an activity is created render in the right language, while `LocalizedApplicationResources`
keeps the *process* on that language across configuration changes that never recreate the activity.
[`05-platform-notes.md`](05-platform-notes.md) explains exactly what goes wrong without each.

## 6. Let a screen change it

```kotlin
fun onLanguagePicked(picked: SupportedLanguage) {
    language.setLanguage(picked.appLanguage)
}
```

`setLanguage` re-applies the platform locale on every call, and updates `languageFlow` and calls
`onLanguageChanged` when the language actually changed.

## 7. "Follow system"

The catalog resolves it — matching the device's language against your list, falling back to the
primary subtag (`pt-BR` → `pt`), then to your fallback language:

```kotlin
val effective: AppLanguage = catalog.resolve(language.language)
```

Call this before reading `isLtr`, before picking a translation, and before passing a language to
`AppLocale`. `AppLanguage.System`'s own `isLtr` is a placeholder, not an answer.

## 8. Compose apps: wire layout direction

```kotlin
implementation("io.github.jamal-wia:kmptoolkit-language-compose")
```

```kotlin
@Composable
fun App(holder: AppLanguageHolder) {
    val selected: AppLanguage by holder.languageFlow.collectAsState()
    AppLocale(language = selected, resolvedLanguage = catalog.resolve(selected)) {
        // the rest of your Compose tree
    }
}
```

Pass both: the selection is what the platform locale is pinned to, and the resolved language is what
the reading direction comes from. See
[`kmptoolkit-language-compose`](../kmptoolkit-language-compose/01-overview.md).

## Next

[`03-guide.md`](03-guide.md) — persistence patterns, the catalog in more depth, and pitfalls worth
knowing before you ship a language picker.
