# kmptoolkit-settings — platform notes

Font scale and theme mode are values this module stores and your UI reads; they behave identically
everywhere. **Language does not.** Applying a language is a platform operation with different
mechanics, different persistence and — most importantly — different *timing* on each platform, and
the difference is large enough to change what your settings screen should do after the user picks
one.

## Permissions and manifest entries

**None.** This module declares no permission, no `<queries>`, no service, and no `Info.plist` key,
and nothing is required of you either. The permission half is asserted rather than merely claimed:
`LibraryManifestTest` reads the merged manifest's requested permissions through a real
`PackageManager` and fails the build if anything but the test harness's own entries appears. (The
module ships no `AndroidManifest.xml` at all, which is what makes the rest true.)

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
- Because the system reapplies it — and because the user can change the language in the OS
  settings without touching your UI — the value the system holds and the value `AppSettings` holds
  can drift. Applying the stored value blindly at start-up then *reverts the user's choice*.
  Reconcile the two first: [`03-guide.md`](03-guide.md#keeping-the-language-applied) has the
  start-up snippet. `LanguageApplier` has no read side because `LocaleManager.applicationLocales`
  has no iOS counterpart, so reading it is Android-specific code in your app.

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
- It does not recreate your activities, and — this is the part that surprises people — it does not
  even change an existing one. An `Activity` carries its own `Configuration`, so its `Resources`
  keep resolving the old locale no matter what the process defaults say; the new language reaches
  contexts created *afterwards*. **Recreate the activity yourself** (`Activity.recreate()`, or
  navigate through a restart) if the user is meant to see the change without relaunching.

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

**One entry, not a chain.** `AppleLanguages` is an ordered *preference list*, and this writes the
chosen tag as its only element, so a string missing from that localization falls back to the
development region rather than to the user's next preferred language. That is the deliberate
trade: preserving the rest of the chain across repeated changes would accumulate the user's
previous app-language choices in it, which is not the system chain either. If the fallback order
matters to your app, write `AppleLanguages` yourself with your own applier — the interface exists
for that.

`synchronize()` is called after the write even though it has been deprecated since iOS 12: the
alternative is an unspecified delay before the value reaches disk, and an app the user kills in
that window loses the language it just said it applied.

### What the language is *not* applied to

On both platforms, this changes string resolution — not number, date or currency formatting driven
by a separate region setting, and not the language of content your server sends you. If your
backend localizes responses, send `settings.language.value?.value` (or the system locale when it
is `null`) as an `Accept-Language` header yourself.

## Swift and Objective-C interop

`FontScale` and `LanguageTag` are Kotlin `value class`es, and Kotlin/Native does not export those
to Objective-C — declarations that use them are omitted from the generated framework header. In
practice that is invisible, because of how this module is meant to be wired: Swift calls
`createLanguageApplier()` and hands the result to shared Kotlin, and everything that *names* a
`FontScale` or a `LanguageTag` — reading the flows, applying the language, building the settings
screen state — lives in common code. If you do need to drive these types from Swift directly,
expose a small Kotlin facade in your shared module that takes and returns `String`/`Float`.

## Storage location

The three values live in whatever `KeyValueStorage` you pass — `SharedPreferences` on Android, an
`NSUserDefaults` suite on iOS. Where exactly that is, and how to inspect it while debugging, is in
[`kmptoolkit-storage`'s platform notes](../kmptoolkit-storage/05-platform-notes.md). The keys are
`SettingsConfig.fontScaleKey`, `.themeModeKey` and `.languageKey`.
