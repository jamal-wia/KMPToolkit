# kmptoolkit-language-compose — Platform notes

`AppLocale` provides the same thing everywhere — `content` rendered in the chosen language — but the
platforms need different mechanisms to get there, and the difference is visible to a user.

## What each platform does

| | Android | iOS | Desktop (JVM) |
|---|---|---|---|
| How a string resource finds the language | Process-global `LocaleList.getDefault()`, re-read when `LocalConfiguration` changes | `NSBundle`, consulted at the moment the lookup composes | Process-global `Locale.getDefault()`, consulted at the moment the lookup composes |
| How `AppLocale` makes a change take effect | Re-pins the process default, then provides a new `LocalConfiguration` and `LocalContext` | Keys the composition on the language code, tearing the subtree down and rebuilding it | Re-pins the process default if something moved it, then keys the composition like iOS |
| `remember`ed state across a language change | **Survives** | **Is lost** | **Is lost** |

That last row is the one to design around. It is not an oversight anywhere: Android has a
composition-local carrying the configuration, so invalidating through it is both cheaper and less
destructive; iOS and the desktop JVM have nothing equivalent, and an already-composed screen would
otherwise keep showing the previous language's strings until something unrelated recomposed it. The
desktop JVM borrows one mechanism from each — a global default to keep pinned, like Android, and a
composition that has to be rebuilt, like iOS.

Do not build a screen that depends on either behaviour. If a language switch must preserve something
— a half-filled form, a scroll position — hoist it above `AppLocale` or into your own state holder,
and it will behave the same on all three.

## Android: why the re-pin is synchronous

The Android implementation re-pins `Locale.getDefault()` and `LocaleList.getDefault()` **in the
composable body, before `content()` composes**, rather than from a `SideEffect`.

A `SideEffect` runs after composition. By then the children have already resolved and cached their
string environment from the wrong locale, and nothing would invalidate that cache until the next
unrelated recomposition — so the screen renders once in the previous language and stays there.

Both defaults are checked before writing, because they can disagree. The framework's own rebuild can
leave `Locale.getDefault()` on the chosen locale while `LocaleList.getDefault()` — the one a string
resource actually reads — still starts with the device's, which happens when the device's locale list
carries the chosen locale at a later index. That case is exactly the residual one
[`kmptoolkit-language`'s platform notes](../kmptoolkit-language/05-platform-notes.md#android)
describes `LocalizedApplicationResources` as unable to cover, and this is what covers it.

## Android: `AppLocale` is not a substitute for the Application wiring

They solve different failures, and an app needs both:

| | Covers |
|---|---|
| `LocalizedApplicationResources` | The process reverting to the device language on a configuration delivery that does not recreate the activity |
| `Activity.attachBaseContext` | The first frame after an activity is created |
| `AppLocale` | The composition itself, including the residual case above |

Skipping the first two and relying on `AppLocale` alone produces an app that is correct while a
screen is composing and wrong everywhere else — notifications, background work, and anything that
reads a string outside Compose.

## `LocalResources` is not provided

In Compose UI 1.10+, `androidx.compose.ui.res.stringResource` reads `LocalResources` rather than
`LocalContext` or `LocalConfiguration`. `AppLocale` does not provide it, so Android XML string
resources looked up that way resolve against the device's language rather than the chosen one.

This affects nothing in a typical Compose Multiplatform app, whose own strings come from
`composeResources` and resolve through the process default that `AppLocale` pins. It matters only if
you also keep strings in Android XML and read them through `androidx.compose.ui.res`. If you do, wrap
the affected subtree yourself — this module deliberately does not, because doing so would change
which strings are translated in an existing app without its author asking for it.
