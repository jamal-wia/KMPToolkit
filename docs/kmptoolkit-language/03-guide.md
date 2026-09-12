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
    English(AppLanguage(code = "en", isLtr = true), "English"),
    Russian(AppLanguage(code = "ru", isLtr = true), "Русский"),
    Arabic(AppLanguage(code = "ar", isLtr = false), "العربية"),
}

val catalog: AppLanguageCatalog = createAppLanguageCatalog(
    supported = SupportedLanguage.entries.map { it.appLanguage },
)
```

`AppLanguageHolder` and `AppLanguageCatalog` only ever see the `AppLanguage` half. Everything a user
reads — the picker's labels, in whatever locale the picker itself is shown in — is your enum's
problem, not this module's.

Note what the catalog is *not*: it does not carry per-language metadata of its own. If your app needs
to know that a language has no CLDR plural rules, or which keyboard layout it maps to, key your own
map by `catalog.idOf(language)` and keep it next to your display names. The catalog answers only the
questions that need the *set* of supported languages to answer.

## Persistence is a callback, not a dependency

`createAppLanguageHolder` takes `initialLanguage` and `onLanguageChanged`, and nothing about storage.
This is deliberate: a storage dependency would force every consumer of this module onto one storage
choice, when the honest answer is that a language selection is one string and almost any storage
already in the app can hold it.

```kotlin
val storage: KeyValueStorage = /* kmptoolkit-storage, or your own */
val holder: AppLanguageHolder = createAppLanguageHolder(
    initialLanguage = catalog.fromId(storage.getString(LANGUAGE_KEY)),
    onLanguageChanged = { storage.putString(LANGUAGE_KEY, catalog.idOf(it)) },
)
```

`idOf` and `fromId` are what make that a two-liner. Encoding `AppLanguage.System` otherwise needs a
sentinel of your own, because `code == null` cannot be written to a string store directly; the
catalog owns that sentinel (`systemId`) so the same value is written and read in one place.

`fromId` never fails. An unknown id — a language you have since dropped, a typo, a first launch with
nothing stored — resolves to `AppLanguage.System`, because stranding a user on a value the app no
longer understands is worse than putting them back on the device's language.

## Migrating an app that already persists a language

If your app already has values on disk from before, do **not** write a migration. Choose `systemId`
to match what is already there, and store ids as before:

```kotlin
val catalog = createAppLanguageCatalog(
    supported = SupportedLanguage.entries.map { it.appLanguage },
    systemId = "system", // whatever your app already writes for "follow the device"
)
```

`idOf` returns a language's own code, so as long as your stored values for real languages were their
codes (`"en"`, `"ar"`, …) and `systemId` matches your existing sentinel, every installed user keeps
their selection. Getting this wrong is silent: the app starts, finds a value it does not recognise,
and quietly puts everyone back on the device language.

## Resolving "follow system"

`AppLanguage.System.isLtr` is a placeholder — `true`, and never meant to be read. `catalog.resolve`
is what turns a selection into a real language:

```kotlin
val effective: AppLanguage = catalog.resolve(holder.language)
```

It matches the device's language — read fresh on every call, so a device language change takes
effect without a restart — against your supported list: the whole tag first, then its primary
subtag, then your fallback. That middle step matters more than it looks: platforms report a full tag
like `"pt-BR"` or `"zh-Hans-CN"`, and an app offering a bare `"pt"` or `"zh"` would otherwise fall
through to its fallback for every regional variant it does not enumerate individually.

`getSystemLanguageCode()` is still there if you need the raw device tag for something else — an
analytics property, a request header. It reports the device's own language independent of whatever
`applyLanguageGlobally` has already set.

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
  Always `catalog.resolve` first, before deriving a direction or picking a translation.
- **Skipping the Android `Application` wiring.** `LocalizedApplicationResources` is not an
  optimisation. Without it the app silently reverts to the device language on configuration changes
  that do not recreate the activity — see [`05-platform-notes.md`](05-platform-notes.md).
- **Calling `applyLanguageGlobally` yourself.** `AppLanguageHolder.setLanguage` already calls it, on
  every call and not only on a change. Calling it again elsewhere just risks two callers disagreeing
  about which one ran last.
- **Forgetting `applyLanguageGlobally` is a genuinely global side effect.** It mutates process-wide
  state — `Locale.getDefault()` on Android, `NSUserDefaults["AppleLanguages"]` on iOS. A test that
  exercises it should restore the previous value afterwards, the way this module's own Android tests
  do.
- **Assuming `AppLanguage.System` is a no-op when applied.** It is not: applying it *clears* whatever
  language was previously forced, so the platform's own language takes back over. It only looks like
  a no-op the first time it is ever applied, when nothing had been overridden yet.

[api]: 04-api-reference.md#getsystemlanguagecode
