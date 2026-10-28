# kmptoolkit-core — Overview

An **internal support artifact**. You do not add it to your build, and nothing in it is a promise to
you. It exists so that two modules can share a piece of code without either one publishing it as
its own API.

It holds three things:

- **`@ToolkitInternalApi`** — the opt-in marker for API that crosses a module boundary inside the
  suite without being part of anyone's public contract (the platform player behind a video surface,
  for instance).
- **`StateMachineLock`** (with its `ReentrantLockHandle` and `newReentrantLock()`) — the lock the
  audio player, the video player and the audio recorder serialize their state machines with.
- **`SharedIdleTimer`** (iOS only, with `IdleTimerHolder` and `IdleTimerHold`) is the one
  reference-counted holder of `UIApplication.idleTimerDisabled`. The system-bars wake lock and the
  video surface both keep the screen on through it.

## Why it exists

`StateMachineLock` used to exist twice: a copy in `kmptoolkit-video-player` and one in
`kmptoolkit-audio-player`, identical but for a paragraph of KDoc. The audio recorder needs it too,
and a third copy of a concurrency primitive is exactly the kind of duplication that drifts — one
copy gets a fix, the others keep the bug. Putting it in one module that the others depend on keeps
a single implementation and a single test suite.

The idle-timer holder is here for a related reason. `UIApplication.idleTimerDisabled` is one
switch per process. `kmptoolkit-systembars` and `kmptoolkit-video-player-compose` each wrote it on
their own, and each undid the other: a wake-lock release let the screen sleep mid-video, or a video
surface restored a value saved while the wake lock held it, leaving the timer disabled for good.
One holder that both modules depend on is the only place a shared count can live.

The marker moved here for the same reason: it was declared in `kmptoolkit-video-player`, so any
other module wanting to mark something internal would have had to depend on a video player.

## What it means for you

- **Do not depend on it directly.** It is pulled in transitively by the modules that use it:
  `kmptoolkit-video-player` exposes it as an `api` dependency (its `@ToolkitInternalApi`
  declarations have to be resolvable from the modules that opt in), `kmptoolkit-audio-player` and
  `kmptoolkit-audio-recorder` use it as an implementation detail, and so does `kmptoolkit-systembars`
  on iOS.
- **`@ToolkitInternalApi` means no compatibility promise.** Every symbol in this artifact can change
  or disappear in any release, including a patch release, without a changelog `Breaking` heading. If
  your code needs `@OptIn(ToolkitInternalApi::class)` to compile, you are depending on an
  implementation detail.
- **It is on the BOM** like every other artifact, so a classpath cannot end up with two different
  releases of it.
- **It publishes `jvm`** only because `kmptoolkit-video-player` does and depends on it — see
  `01-architecture.md` § "Desktop targets".

## What this is not

- **Not a utilities module.** Code does not move here because two modules *might* want it; it moves
  when a second module demonstrably needs the same implementation.
- **Not a place for public API.** Anything a consumer is meant to call lives in the module that
  owns the capability.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — why there is nothing to set up
- [`03-guide.md`](03-guide.md) — the one scenario where you meet this artifact: an opt-in error
- [`04-api-reference.md`](04-api-reference.md) — the symbols and their contracts
