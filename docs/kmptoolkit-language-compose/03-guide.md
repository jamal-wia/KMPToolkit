# kmptoolkit-language-compose — Guide

## What already auto-mirrors once `LocalLayoutDirection` is set

`AppLocale` providing the right `LocalLayoutDirection` is enough, with no extra modifier, for:

- `Row` child ordering and `Arrangement.Start` / `End`
- `Modifier.padding(start = …, end = …)` and `Modifier.align(Alignment.Start / End)`
- `TextAlign.Start` / `TextAlign.End`
- `HorizontalPager` swipe direction
- Material components (`TopAppBar`, `NavigationBar`, `ModalDrawerSheet`, …)
- `Icons.AutoMirrored.*` (e.g. `Icons.AutoMirrored.Filled.ArrowBack`)

None of these need `mirrorOnRtl` or `mirrorOnLtr`. Reaching for either on something already in this
list double-flips it.

## What does not auto-mirror, and what to do about each

| Case | What to do |
|---|---|
| A custom directional drawable used as a "next / drill-in" affordance (a project-owned `ic_chevron_right`, say) | `Modifier.mirrorOnRtl()` |
| A drawable that points the wrong way by design and is meant to be pre-flipped to the leading side (a back button drawn from a "chevron right" asset) | `Modifier.mirrorOnLtr()` — not a hardcoded `graphicsLayer { scaleX = -1f }`, which is correct under one direction and silently wrong under the other |
| `Modifier.absolutePadding` / `Modifier.absoluteOffset` | Direction-blind by design. Use the non-`absolute` variants unless that is genuinely what you want |
| `TextAlign.Left` / `TextAlign.Right` | Don't use them — always `Start` / `End` |
| Media playback controls (skip back/forward, rewind, fast-forward) | **Do not mirror.** A media timeline reads left-to-right regardless of language, per both Material and Apple's guidance — unless your own product deliberately chooses otherwise, which is a product decision, not an automatic one |
| Horizontal slide animations (`slideInHorizontally`, `graphicsLayer { translationX = … }`) | Multiply the offset by `if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f` |
| Vertical animations | Direction-independent — no action |
| `Canvas` / `DrawScope` drawing | Not affected — `DrawScope` always uses the local pixel coordinate system |

## Pitfalls

- **Placing `AppLocale` below the root.** It must wrap the whole Compose tree. Anything composed
  outside it sees `LayoutDirection.Ltr` regardless of the selected language, producing a
  split-personality UI where part of the screen mirrors and part does not.
- **Hardcoding `graphicsLayer { scaleX = -1f }` instead of `mirrorOnRtl()` / `mirrorOnLtr()`.** It
  looks identical under the direction you tested and is backwards under the other one.
- **Mirroring an icon that embeds text or digits.** Both modifiers flip the whole node, glyphs
  included. Reposition such an icon instead of mirroring it.
- **Assuming a language change costs, or keeps, the subtree's state on both platforms.** It differs:
  Android invalidates string resources through `LocalConfiguration`, so `remember`ed state survives a
  switch; iOS has no such mechanism and tears the subtree down instead. Do not build a screen that
  depends on either behaviour — see [`05-platform-notes.md`](05-platform-notes.md).
- **Passing an already-resolved language as `AppLocale`'s only argument when the user picked
  "follow system".** That pins the language the device is set to *today* rather than the device's
  own, so a later device language change stops reaching the app. Pass the selection as `language`
  and the resolved one as `resolvedLanguage`.
