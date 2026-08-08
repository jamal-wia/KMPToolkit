# kmptoolkit-settings — overview

The three display preferences almost every app ends up needing — **how large the text is, which
colour scheme it uses, and which language it speaks** — persisted across restarts and exposed as
`StateFlow`s the UI collects.

```kotlin
val (settings, problems) = createAppSettings(storage)

settings.themeMode.value          // ThemeMode.SYSTEM
settings.setThemeMode(ThemeMode.DARK)
```

Every app writes this class. It is fifty lines, it is never interesting, and it goes wrong in the
same three places each time: a value that fails to persist is shown to the user anyway, a
preference from an older version of the app crashes the parse, and the "system default" case gets
confused with "the user never chose". This module is those fifty lines with those three cases
handled and asserted.

## What it gives you

- **One `AppSettings` interface** over any `KeyValueStorage` from `kmptoolkit-storage`, with three
  flows and three setters.
- **Typed outcomes instead of silence.** A write that the store rejected returns
  `SettingsError.WriteFailed` and does **not** move the flow; a value that could not be read or
  parsed is reported in `SettingsLoad.problems` rather than quietly becoming the default.
- **A font scale you define.** `FontScale` is a validated multiplier in `0.5..3.0`, not a fixed
  list of steps — see below.
- **A language you define.** `LanguageTag` is a canonicalised BCP 47 tag, not an enum of
  languages, and the set your app supports is `SettingsConfig.supportedLanguages`.
- **A `LanguageApplier`** that hands the chosen language to the platform: the framework
  `LocaleManager` on Android 13+, the process locale defaults below it, `AppleLanguages` on iOS.
  What each actually does — and when it takes effect — is in
  [`05-platform-notes.md`](05-platform-notes.md), and the differences are large enough to read
  before designing your settings screen.

## What this is not

- **Not a settings UI.** No Compose dependency, no picker, no row, no labels. This module holds
  state; rendering it is yours. That is also why there is no display name for a language: the
  string a user reads for `pt-BR` is copy, and copy is the app's — see
  [`03-guide.md`](03-guide.md) for the two-line `Locale`/`NSLocale` call that produces one.
- **Not a typography scale.** No `LARGE`, no `EXTRA_LARGE`, no `×1.15`. Which steps you offer and
  what they are called is a product decision; the library ships the value type and the range, and
  [`03-guide.md`](03-guide.md) shows the three-line enum if steps are what you want.
- **Not a list of languages.** A library cannot enumerate its consumers' translations, and an enum
  here would mean a new locale needs a new version of *this* library.
- **Not a live view of the store.** The flows are populated once at construction and updated by
  this instance's own writes. A second `AppSettings` over the same storage — or another process —
  does not push changes here. Construct one, hold it for the app's lifetime.
- **Not a session or logout hook.** The module this was ported from carried a
  `BiometricSessionCleaner` that switched a biometric preference off on logout. It is deliberately
  absent: it coupled settings to a session manager and to a biometric module for one app's policy
  decision, and the same thing is three lines in your own code — the snippet is in
  [`03-guide.md`](03-guide.md). This module also stores no biometric preference at all; that
  belongs next to the biometric gate, not next to the font scale.
- **Not encrypted.** These are preferences, not secrets. Pass a plain `KeyValueStorage`.
- **Not a `-testing` artifact.** There is nothing here worth a second published artifact: the
  double you need is a store, and `kmptoolkit-storage-testing` already ships
  `InMemoryKeyValueStorage`. `createAppSettings(InMemoryKeyValueStorage())` is a real
  `AppSettings` with real behaviour, which is a better fixture than a hand-written fake could be,
  and `LanguageApplier` is a `fun interface` you substitute with a lambda. See
  [`03-guide.md`](03-guide.md#testing).

## Where it fits

| You want | Use |
|---|---|
| A user-chosen font scale, theme, or language, remembered across launches | this module |
| Any other small value to survive a restart | [`kmptoolkit-storage`](../kmptoolkit-storage/01-overview.md) |
| A secret to survive a restart | `SecureKeyValueStorage` in `kmptoolkit-storage` |
| To know whether the *system* is in dark mode right now | your UI toolkit (`isSystemInDarkTheme()`) |
| To know the *system* language | the platform (`Locale.getDefault()`, `NSLocale`) |

## Dependencies

`kmptoolkit-storage` (a `KeyValueStorage` is a parameter of `createAppSettings` and a
`StorageError` is carried by `SettingsError`) and `kotlinx-coroutines-core` (the flows). Nothing
else — no Compose, no AppCompat, no DI framework.

Next: [`02-getting-started.md`](02-getting-started.md).
