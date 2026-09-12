# kmptoolkit-language — Overview

App language selection, and the platform-locale side effect that goes with it: an
`AppLanguageHolder` you create and own, an `AppLanguage` of just a language tag and a reading
direction, and `applyLanguageGlobally()` to push the choice onto the platform's own default locale.

## The problem it solves

Letting a user pick an in-app language touches two things that live outside Compose:

1. **The platform's own string-resource lookup.** Android's `stringResource` and iOS's
   `NSLocalizedString` both resolve against the *process's* current locale, not against whatever a
   Composition happens to be showing. A language switcher that only changes what a screen renders,
   and never touches `Locale.setDefault` / `AppleLanguages`, leaves every string looked up from
   outside Compose — a notification, a background service, a `getString()` call in platform code —
   in the previous language.
2. **Reading direction.** Choosing Arabic, Hebrew, Persian or Urdu has to flip the whole UI's layout
   direction, not just translate the strings on screen. Direction is a derived property of the
   language, and a module that only stores a code and forgets direction pushes that derivation onto
   every consumer separately.

`AppLanguageHolder` answers "what is selected right now, and who does it notify". A single
`applyLanguageGlobally()` call answers the platform side effect. Everything else — which languages
exist, what they're called, storage — is deliberately left to you.

## What this module deliberately does not carry

- **No language catalog.** There is no enum of "every language this module knows about". You decide
  which languages your app offers; `AppLanguage` is a plain `(code, isLtr)` pair you construct for
  each one.
- **No display name, no flag, no user-facing text at all.** A display string like `"Français"` or a
  flag emoji is exactly the kind of user-facing text this suite's modules never carry — see
  [`../01-architecture.md`](../01-architecture.md). Attach labels to your own list, in your own
  copy, in whatever languages your app is translated into.
- **No storage.** `createAppLanguageHolder` takes the language to start from and a callback to
  persist a change — it does not read or write any storage of its own, `kmptoolkit-storage` or
  otherwise. See [`03-guide.md`](03-guide.md).
- **No Compose dependency.** `AppLanguageHolder` and `applyLanguageGlobally` are plain Kotlin, so a
  consumer that reads the current language from non-Compose code (a notification, a background
  worker) does not pull in a UI framework to do it. The layout-direction wiring a language choice
  implies lives in the separate `kmptoolkit-language-compose` module.

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — create a holder, wire a screen that changes it.
- [`03-guide.md`](03-guide.md) — persistence, resolving "follow system", and the pitfalls worth
  knowing before you build a language picker.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`05-platform-notes.md`](05-platform-notes.md) — what Android and iOS actually do, and the one
  Android subtlety worth reading before you ship a language switcher.
- `kmptoolkit-language-compose` — `AppLocale` (provides `LocalLayoutDirection`, forces a
  recomposition on language change) and `Modifier.mirrorOnRtl()` / `mirrorOnLtr()`.
