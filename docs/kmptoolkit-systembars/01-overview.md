# kmptoolkit-systembars — Overview

Control of the status bar and navigation bar for a Compose Multiplatform app: a controller you
create and own, one base configuration your theme writes, and per-axis claims your screens push and
release.

## The problem it solves

The system bars are global mutable state, and every screen in the app can write to them. That is
the whole difficulty. The usual result:

1. The photo viewer sets light icons, because its background is a dark image.
2. From it, the user opens the share sheet, which sets dark icons for its own light surface.
3. The share sheet closes.
4. The status bar is now dark-on-dark, and stays that way until something happens to write to it
   again.

Nobody made a mistake at any one step. The bug is that there is no answer to "what should the bars
look like once *I* am gone" — every screen writes an absolute value, and the last write wins
forever.

The half-fix — snapshot the current configuration on entry, write it back on exit — trades that
bug for a subtler one. The snapshot goes stale: if the app switched to dark mode while the viewer
was open, or if another still-visible surface set something in the meantime, writing the old value
back undoes their work. Restoring is only correct if you restore *the state that would apply if you
had never been there*, and a value copied on entry is not that.

## How this module answers it

There is one [`SystemBarsController`](04-api-reference.md#systembarscontroller) holding a
**configuration made of three independently owned axes** — the status bar's icon style, the
navigation bar's icon style, and visibility — and a **stack of claims** on top of a base:

```kotlin
// The theme, and only the theme, owns the base.
controller.setBaseConfig(if (darkTheme) SystemBarsConfig.ForDarkBackground else SystemBarsConfig.ForLightBackground)

// A screen claims one axis for as long as it is composed.
@Composable
fun PhotoViewerScreen(controller: SystemBarsController) {
    SystemBarsEffect(controller, statusBarIcons = SystemBarIconStyle.LightIcons)
    // ...
}
```

Two properties follow from that shape, and they are the reason it exists:

- **A claim names only the axes it cares about.** The photo viewer claims the status bar's icons; a
  video player claims visibility. Both can be on screen, neither has to know about the other, and
  neither can overwrite the other's axis.
- **Leaving restores by removal, not by replay.** `SystemBarsEffect` drops the claim when the
  composable leaves composition, and the controller recomputes from what is left. If the theme
  changed while the screen was up, the theme's new value is what shows. Nothing that happened in
  between is undone.

## What this is **not**

- **Not a theming system.** It has no concept of a colour scheme, a Material theme, dark mode, or
  when any of those should change. It takes a configuration and applies it; deciding *which*
  configuration belongs to your light and dark themes is your app's call, and one line at the place
  your theme already switches.
- **Not an insets library.** It does not tell you how tall the status bar is, does not pad anything
  around it, and does not consume insets. Compose Multiplatform already ships that:
  `WindowInsets.statusBars`, `WindowInsets.navigationBars`, `WindowInsets.safeDrawing`, and the
  `Modifier.windowInsetsPadding` family. Use those for layout; use this for appearance.
- **Not an edge-to-edge switch.** Whether your app draws behind the bars is an app-wide layout
  decision — on Android it is `enableEdgeToEdge()` in your activity — and a library that flipped it
  from inside a "set the icon colour" call would change how every screen measures. See
  [`05-platform-notes.md`](05-platform-notes.md).
- **Not a bar background colour.** There is no `statusBarColor` in the configuration. On an
  edge-to-edge app the bars are transparent and the colour behind them is drawn by your own UI, and
  the platform properties that used to set it are deprecated and inert on current Android.
- **Not automatic.** It does not sample what is drawn under the bars to pick a contrasting icon
  colour. That is a real technique and a real cost — per-pixel sampling on a timer for as long as
  it is on screen — and it belongs to the screen that needs it, not to every consumer of this
  module.
- **Not a singleton.** Nothing here is global or static. Two controllers driving one window would
  reintroduce exactly the fight this module exists to end, so create one and pass it.

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — a working setup on both platforms.
- [`03-guide.md`](03-guide.md) — ownership in practice, and the cases worth understanding.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`05-platform-notes.md`](05-platform-notes.md) — what Android and iOS actually do, and what only
  one of them can.
