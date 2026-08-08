# kmptoolkit-systembars — Guide

## The problem this module is shaped around: two screens fighting

System-bar state is global and every screen can write it. Here is the failure, concretely.

A settings screen has a light background, so it wants dark icons. A photo viewer has a dark
background, so it wants light ones. Both do the obvious thing:

```kotlin
// Don't do this.
LaunchedEffect(Unit) { controller.setStatusBarIcons(SystemBarIconStyle.DarkIcons) }   // settings
LaunchedEffect(Unit) { controller.setStatusBarIcons(SystemBarIconStyle.LightIcons) }  // viewer
```

Open settings, open the viewer from it, go back. The viewer's write is still the last one, so the
settings screen now has light icons on a light background: an invisible status bar, on a screen
that never did anything wrong. Nothing announced the bug — the screen that broke is not the screen
that wrote.

### Why "save and restore" is not enough

The reflex fix is for the viewer to save the value on entry and put it back on exit:

```kotlin
// Better, still wrong.
val previous = remember { controller.currentConfig }
DisposableEffect(Unit) {
    controller.setConfig(dark)
    onDispose { controller.setConfig(previous) }
}
```

This is right exactly when nothing else changed in between, which is not a promise anyone can make:

- The user flips the system into dark mode while the viewer is open. The viewer exits and writes
  back the light configuration it captured on entry. The theme's change is silently reverted, and
  the theme has no idea it needs to write again.
- A second surface — a bottom sheet, an overlay, a still-visible parent — set something of its own
  while the viewer was up. The viewer's restore erases it.

The problem is not the mechanism, it is the question. "What was the state when I arrived" is not
the same question as "what should the state be now that I am gone", and only the second one has a
correct answer.

## What this module does instead

Two ideas, and everything else follows from them.

### 1. Three axes, owned separately

A `SystemBarsConfig` is not one value. It is three:

| Axis | What it decides |
|---|---|
| `statusBarIcons` | dark or light icons in the status bar |
| `navigationBarIcons` | dark or light icons in the navigation bar |
| `visibility` | which bars are on screen, and what a swipe does to a hidden one |

A `SystemBarsOverride` claims each axis independently, and `null` means "not mine":

```kotlin
SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons)          // claims one axis
SystemBarsOverride(visibility = SystemBarsVisibility.Immersive)            // claims another
```

A photo viewer claiming icons and a video player claiming visibility are simply not in conflict,
and neither has to know the other exists. Only when two claims name the *same* axis is there
anything to arbitrate.

### 2. A stack, not a variable

Claims are not written into the configuration. They are pushed onto a stack over a base, and the
effective configuration is recomputed from base upward every time the stack changes:

```
effective = base
            └─ + override pushed by the screen behind
               └─ + override pushed by the screen in front   ← wins any axis it shares
```

`SystemBarsEffect` pushes when a composable enters composition and removes when it leaves.
"Restoring" is therefore not a write at all — it is the removal of one claim followed by a
recompute, which by construction produces *the state that would apply if this screen had never been
there*. The two failures above stop existing:

- The theme changed while the viewer was up? The theme wrote the **base**, underneath the claim. It
  was never overwritten, and it is what the recompute finds.
- Another surface claimed something? That is a different layer, and removing this one does not
  touch it.

### The base belongs to the theme

One writer, one axis-set: the base is the answer to "what should the bars look like when no screen
has an opinion", and that is a theming question.

```kotlin
LaunchedEffect(darkTheme) {
    controller.setBaseConfig(
        if (darkTheme) SystemBarsConfig.ForDarkBackground else SystemBarsConfig.ForLightBackground,
    )
}
```

A screen that calls `setBaseConfig` is doing the broken thing from the top of this page with extra
steps: the value it writes outlives it.

## Cases worth understanding

### Two claims on the same axis

The later one — later in composition order, which for a navigation stack is the screen in front —
wins. When it goes away the axis returns to the one underneath, not to the base:

```kotlin
SystemBarsEffect(controller, statusBarIcons = SystemBarIconStyle.LightIcons)   // outer screen
if (detailOpen) {
    SystemBarsEffect(controller, statusBarIcons = SystemBarIconStyle.DarkIcons) // detail, wins
}
```

Close the detail and the status bar goes back to light, because the outer screen still holds its
claim.

### A claim that changes while the screen is up

Pass the new value; the effect updates the existing layer **in place**:

```kotlin
val icons = if (scrolledPastHeader) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons
SystemBarsEffect(controller, statusBarIcons = icons)
```

In place matters. Releasing and re-pushing would move the layer to the top of the stack, so a
background screen that merely re-evaluated its own override would quietly overtake the screen in
front of it. Doing this by hand — `applyOverride` — has the same rule: use
`SystemBarsOverrideHandle.update`, never release-then-apply.

### Claiming outside composition

Some owners are not composables — a navigation component, a session-scoped object. `applyOverride`
returns a handle, and the rule is only that whoever takes it releases it:

```kotlin
private val fullscreen: SystemBarsOverrideHandle =
    controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

fun onDestroy() {
    fullscreen.release()
}
```

A dropped handle pins its layer for the controller's lifetime, and every axis it claims stays
claimed. Prefer `SystemBarsEffect` wherever there is a composition to tie the claim to — it cannot
forget.

### Immersive vs. hidden

Both hide the bars. They differ in what the user can do about it:

- `SystemBarsVisibility.Immersive` — a swipe from the edge brings the bars back translucently, then
  they hide again. This is what a video player, a photo viewer or a game wants.
- `SystemBarsVisibility.Hidden` — the bars stay gone; a swipe does nothing. Only for a surface that
  offers its own way out, such as a kiosk. Ship this by accident and the user is trapped.

### Rotation and activity recreation (Android)

Nothing to do. A rotation destroys the window and builds a new one at platform defaults, and the
controller re-applies its current configuration as soon as the new activity resumes — that is what
the `ActivityAccess` it was built with is for. Both the base and every live claim survive, because
they live in the controller, not in the window.

Process death is different: the controller is gone with everything else, and the app rebuilds it
from its own restored state. There is nothing to persist here — the theme sets the base again on
first composition, and each screen re-claims when it re-composes.

### Threading

Every method is safe to call from any thread. State transitions are a compare-and-set over the
whole stack, so two writers cannot interleave into a lost axis — a theme updating the base on a
background dispatcher while a screen pushes a claim on the main thread leaves both changes in
place. Platform work is moved to the main thread by the implementation.

Atomicity is not ordering, though: if two writers race to set the *same* axis, which one lands
last is genuinely undefined, because the question has no answer. Two owners of one axis is the
design problem, and the layer stack is how you avoid having it.

## Anti-patterns

| Don't | Do |
|---|---|
| `setBaseConfig` from a screen | `SystemBarsEffect` — the base outlives the screen |
| Snapshot on entry, write back on exit | Let the claim be released; the recompute is the restore |
| One override claiming all three axes "to be safe" | Claim only what the screen actually decides |
| `handle.release()` then `applyOverride(new)` | `handle.update(new)` — keeps the layer's precedence |
| A second controller for a second window | One controller; use `DialogWindowSystemBarsEffect` for dialog windows |
| Hiding the bars with `Hidden` for a video player | `Immersive` — the user needs a way back |
