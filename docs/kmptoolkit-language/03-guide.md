# kmptoolkit-language — Guide

## Why this module carries no language list

The reflex design for a "language module" is a fixed enum: every language the app offers, with a
display name and maybe a flag. That is exactly what this module refuses to be, for a rule that holds
across the whole suite — see [`../01-architecture.md`](../01-architecture.md): no user-facing text
ships in a library module. A display name is text meant to be read by a user; it belongs in your
app's own copy, in every language you actually translate into, not in a dependency's compiled code.

The consequence is that *you* own the list:

```kotlin
enum class SupportedLanguage(val appLanguage: AppLanguage, val displayName: String) {
    System(AppLanguage.System, "System"),
    English(AppLanguage(code = "en", isLtr = true), "English"),
    Russian(AppLanguage(code = "ru", isLtr = true), "Русский"),
    Arabic(AppLanguage(code = "ar", isLtr = false), "العربية"),
}
```

`AppLanguageHolder` only ever sees the `AppLanguage` half. Everything a user reads — the picker's
labels, in whatever locale the picker itself is shown in — is your enum's problem, not this module's.

## Persistence is a callback, not a dependency

`createAppLanguageHolder` takes `initialLanguage` and `onLanguageChanged`, and nothing about storage.
This is deliberate: a storage dependency would force every consumer of this module onto one storage
choice, when the honest answer is that a language selection is one string and almost any storage
already in the app can hold it.

```kotlin
val storage: KeyValueStorage = /* kmptoolkit-storage, or your own */
val holder: AppLanguageHolder = createAppLanguageHolder(
    initialLanguage = storage.getString(LANGUAGE_KEY)?.let { code ->
        AppLanguage(code = code.takeIf { it != SYSTEM_MARKER }, isLtr = isLtrCode(code))
    } ?: AppLanguage.System,
    onLanguageChanged = { language ->
        storage.putString(LANGUAGE_KEY, language.code ?: SYSTEM_MARKER)
    },
)
```

Encoding `AppLanguage.System` needs one bit of your own design, because `code == null` cannot be
written to a plain string store directly — a sentinel string (`SYSTEM_MARKER` above) or a separate
boolean flag both work; pick whichever your storage already makes easy.

## Resolving "follow system"

`AppLanguage.System.isLtr` is a placeholder — `true`, and never meant to be read. The one thing this
module gives you to resolve it is [`getSystemLanguageCode()`][api], which reports the device's own
preferred language independent of whatever `applyLanguageGlobally` has already set. Match it against
your own supported list, and fall back to whatever language you'd want a device to see if its
language isn't one you translate into:

```kotlin
fun resolve(selected: AppLanguage, supported: List<AppLanguage>): AppLanguage {
    if (selected != AppLanguage.System) return selected
    val deviceCode: String? = getSystemLanguageCode()
    return supported.firstOrNull { it.code == deviceCode }
        ?: supported.firstOrNull { it.code == deviceCode?.substringBefore('-') }
        ?: supported.first()
}
```

The second `firstOrNull` matters more than it looks: platforms report a full tag like `"pt-BR"` or
`"zh-Hans-CN"`, and an app that only offers a bare `"pt"` or `"zh"` would otherwise fall through to
its fallback language for every regional variant it does not enumerate individually.

## Two writers of the platform locale is the bug to avoid

`applyLanguageGlobally` is a global process-wide side effect — `Locale.setDefault` /
`LocaleList.setDefault` on Android, the `AppleLanguages` array on iOS. `AppLanguageHolder` is meant
to be the **only** caller: if something else in your app also calls `Locale.setDefault` directly (an
onboarding flow that defaults to the device language before the holder exists, say), the two will
race on process start, and whichever ran last wins until the holder's next `setLanguage`. Route every
locale change through one holder.

## Pitfalls

- **Reading `holder.language.isLtr` for layout direction when the selection might be `System`.**
  `AppLanguage.System.isLtr` is a placeholder that says nothing about the actual device language.
  Always resolve first — see above — before deriving a direction or picking a translation.
- **Calling `applyLanguageGlobally` yourself.** `AppLanguageHolder.setLanguage` already calls it.
  Calling it again elsewhere just risks two callers disagreeing about which one ran last.
- **Forgetting `applyLanguageGlobally` is a genuinely global side effect.** It mutates process-wide
  state — `Locale.getDefault()` on Android, `NSUserDefaults["AppleLanguages"]` on iOS. A test that
  exercises it should restore the previous value afterwards, the way this module's own Android tests
  do.
- **Assuming `AppLanguage.System` is a no-op when applied.** It is not: applying it *clears* whatever
  language was previously forced, so the platform's own language takes back over. It only looks like
  a no-op the first time it is ever applied, when nothing had been overridden yet.

[api]: 04-api-reference.md#getsystemlanguagecode
