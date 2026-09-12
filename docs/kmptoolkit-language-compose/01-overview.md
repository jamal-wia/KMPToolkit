# kmptoolkit-language-compose — Overview

Compose Multiplatform wiring for `kmptoolkit-language`: `AppLocale` provides `LocalLayoutDirection`
from an `AppLanguage` and forces a recomposition on language change, and `Modifier.mirrorOnRtl()` /
`mirrorOnLtr()` flip a directional drawable that has no Compose auto-mirrored counterpart.

## Why a separate module

`kmptoolkit-language` itself has no Compose dependency, so a consumer who only needs
`AppLanguageHolder` from non-Compose code — a background worker, a notification, plain shared
business logic — never pulls in a UI framework to get it. The Compose-specific pieces that a
language choice implies (layout direction, drawable mirroring) live here instead, the same split
`kmptoolkit-uploader` / `kmptoolkit-uploader-sqldelight` uses for an optional dependency that not
every consumer needs.

## What this module is not

- **Not a string-resource system.** It does not load, cache, or select translated strings. Compose
  Multiplatform's own `stringResource` already does that, keyed off the platform locale
  `kmptoolkit-language`'s `applyLanguageGlobally` sets; this module only makes sure Compose knows
  which *direction* to lay content out in, and forces it to re-resolve strings when the language
  changes.
- **Not automatic mirroring.** `mirrorOnRtl` / `mirrorOnLtr` are opt-in, applied per-drawable. Most
  layout — `Row` ordering, `Arrangement.Start`/`End`, `TextAlign.Start`/`End`, Material components —
  already auto-mirrors once `LocalLayoutDirection` is set correctly; these two modifiers exist for
  the minority of cases that don't, and only for the ones you actually apply them to.

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — wrap your app root, mirror your first icon.
- [`03-guide.md`](03-guide.md) — what auto-mirrors on its own, what does not, and where to apply each
  modifier.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
