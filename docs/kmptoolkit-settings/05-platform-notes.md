# kmptoolkit-settings — platform notes

Font scale and theme mode are values this module stores and your UI reads; they behave identically
everywhere. **Language does not.** Applying a language is a platform operation with different
mechanics, different persistence and — most importantly — different *timing* on each platform, and
the difference is large enough to change what your settings screen should do after the user picks
one.

## Permissions and manifest entries

**None.** This module declares no permission, no `<queries>`, no service, no `Info.plist` key, and
nothing is required of you either. That is asserted, not just claimed: `LibraryManifestTest` reads
the merged manifest through a real `PackageManager` and fails the build if anything but the test
harness's own entries appears.

Changing the app language needs no permission on either platform. (`CHANGE_CONFIGURATION` exists
on Android and is signature-level — no app can hold it, and nothing here tries.)

## Applying a language

| | Android 13+ (API 33) | Android 12 and below | iOS |
|---|---|---|---|
| Mechanism | `LocaleManager.setApplicationLocales` | `Locale.setDefault` + `LocaleList.setDefault` | `AppleLanguages` in `NSUserDefaults` |
| Persisted by the platform | **yes** — reapplied before your code runs | no — only by `AppSettings` | yes |
| Visible in system settings | yes, under Apps → *your app* → Language | no | yes, under Settings → *your app* |
| Effect on the running app | the system recreates your activities | resources loaded *afterwards* | usually none until relaunch |
| You must call it at start-up | not necessary | **yes** | yes |

### Android 13 and above

`createLanguageApplier(context)` calls `LocaleManager.setApplicationLocales`. This is the real
per-app language feature: the system stores the choice itself, lists your app in the OS language
settings, **recreates your activities** so the new language takes effect immediately, and reapplies
it on every later launch before any of your code runs.

Two consequences worth designing for:

- Your activity is destroyed and recreated the moment the user picks a language. Anything in
  unsaved UI state disappears — the same event as a rotation, so if you already survive rotation,
  you already survive this.
- Because the system reapplies it, the value in this module's store and the value the system holds
  can drift if something else changes one of them. `AppSettings` remains the source of truth for
  *your* UI; if you want to detect drift, read `LocaleManager.applicationLocales` at start-up and
  compare.

`null` (follow the system) sets an **empty** locale list, which clears the override. It
deliberately does not pin today's system locale, or the app would stop following the system the
moment the user changed it.

### Android 12 and below

There is no per-app language in the framework, so the applier sets the process defaults:
`Locale.setDefault` **and** `LocaleList.setDefault`. Both, because resource resolution reads
`LocaleList.getDefault()` on API 24+; setting only the JVM default lets the framework's periodic
reapplication of the base `Configuration` silently win, and the app reverts to the system language
at an unpredictable moment.

What this does **not** do:

- It does not persist anything of its own — this module's store is the only record, so you must
  apply the loaded language at start-up (the collector in
  [`03-guide.md`](03-guide.md#keeping-the-language-applied)).
- It does not recreate your activities. Already-inflated views and already-resolved strings keep
  the old language; anything resolved afterwards gets the new one. If you want an immediate
  switch, recreate your activity yourself after applying.

**Why not `AppCompatDelegate.setApplicationLocales`**, which would give this path persistence and
recreation for free: it would put `androidx.appcompat` — a UI toolkit with its own resources and
activity base classes — on the compile classpath of every consumer of a *settings* library,
including the Compose-only apps that worked to get rid of it. If your app already uses AppCompat
and wants that behaviour, pass your own two-line applier instead of the factory; `LanguageApplier`
is a `fun interface` precisely so that substitution costs nothing:

```kotlin
val applier = LanguageApplier { language ->
    AppCompatDelegate.setApplicationLocales(
        language?.let { LocaleListCompat.forLanguageTags(it.value) } ?: LocaleListCompat.getEmptyLocaleList(),
    )
}
```

### iOS

`createLanguageApplier()` writes the tag as the single entry of `AppleLanguages` in the standard
`NSUserDefaults`, and removes the key for "follow the system". That is the preference iOS itself
reads: it shows up in Settings → *your app* → Preferred Language, it is persisted by the system,
and it survives reinstalls of the app's own storage.

**It generally does not change the running app.** `NSBundle` resolves its localization when the
bundle is created, and the main bundle exists long before any of this runs, so strings already
resolved through `NSLocalizedString` — and anything that cached them — keep the old language until
the app is relaunched. There is no supported API to make a running iOS app re-resolve its main
bundle.

Two workable designs:

1. **Restart-based.** Apply the language, tell the user it takes effect next time the app starts.
   Honest, trivial, and what many iOS apps do.
2. **Own your strings.** Route display strings through your own locale state — Compose
   Multiplatform resources can select a locale at read time — and drive that state from
   `settings.language`. Then use `LanguageApplier` only to keep the *system-level* preference in
   step, so that OS surfaces (the share sheet, permission dialogs, Settings) agree with your UI.

`synchronize()` is called after the write even though it has been deprecated since iOS 12: the
alternative is an unspecified delay before the value reaches disk, and an app the user kills in
that window loses the language it just said it applied.

### What the language is *not* applied to

On both platforms, this changes string resolution — not number, date or currency formatting driven
by a separate region setting, and not the language of content your server sends you. If your
backend localizes responses, send `settings.language.value?.value` (or the system locale when it
is `null`) as an `Accept-Language` header yourself.

## Storage location

The three values live in whatever `KeyValueStorage` you pass — `SharedPreferences` on Android, an
`NSUserDefaults` suite on iOS. Where exactly that is, and how to inspect it while debugging, is in
[`kmptoolkit-storage`'s platform notes](../kmptoolkit-storage/05-platform-notes.md). The keys are
`SettingsConfig.fontScaleKey`, `.themeModeKey` and `.languageKey`.
