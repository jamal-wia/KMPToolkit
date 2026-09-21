# kmptoolkit-video-player-vlcj — Overview

The desktop (JVM) playback engine for [`kmptoolkit-video-player`](../kmptoolkit-video-player/01-overview.md),
built on [VLCJ](https://github.com/caprica/vlcj) and the VLC media player's `libvlc`.

```kotlin
// desktopMain / jvmMain
val player: VideoPlayer = createVlcjVideoPlayer()
```

That is the whole desktop-specific surface. What comes back is the same `VideoPlayer` Android and
iOS get — the same state machine, the same flows, the same release contract — so every line of
shared code that drives a player runs unchanged on desktop. The picture is decoded into memory and
drawn by `kmptoolkit-video-player-compose`; there is no native window, AWT `Canvas` or Swing
component to embed.

## The problem it solves

`kmptoolkit-video-player` ships engines for Android (Media3 ExoPlayer) and iOS (AVPlayer) because
those platforms come with a video stack. A desktop JVM does not. Something native has to decode the
video, and every candidate brings a licence, an installation requirement or both. So the core
module ships **no** desktop engine, and each candidate lives in its own artifact that only an app
choosing it pulls in:

| Artifact | Engine | Licence of the engine | Needs installed |
|---|---|---|---|
| `kmptoolkit-video-player-vlcj` (this one) | VLC through VLCJ | **GPL-3.0** (or a commercial VLCJ licence) | VLC 3.x |
| `kmptoolkit-video-player-javafx` | JavaFX Media | GPL-2.0 with Classpath Exception | nothing beyond JavaFX |

VLC plays practically anything — every common container and codec, HLS, most network protocols —
which is why you would pick this one. The price is the licence and the external install, both
described below. Read both before adopting it.

## Licence — read this before you depend on it

- **This artifact is MIT**, like the rest of KMPToolkit.
- **It depends on VLCJ, which is GPL-3.0.** VLCJ is also sold under a commercial licence by its
  author (Caprica Software) for applications that cannot meet the GPL.
- **libvlc and most VLC modules are LGPL-2.1-or-later**, some modules GPL-2.0-or-later. You link
  against whatever copy of VLC is installed; if you bundle VLC, you are distributing it and its
  licences apply to that distribution too.

What that means for you, in plain terms (this is orientation, not legal advice): an application
that you *distribute* with this artifact on its runtime classpath is a work combined with VLCJ, so
unless you hold a commercial VLCJ licence, the application as a whole must be distributable under
GPL-3.0 terms — including offering its complete corresponding source to everyone you give the
binary to. A closed-source desktop app therefore either buys the VLCJ commercial licence or uses
`kmptoolkit-video-player-javafx` instead. Code that is never distributed (an internal tool) is not
affected in the same way.

Nothing in KMPToolkit's other modules depends on this artifact — not the core player, not the
Compose module, not the BOM's constraints (a BOM only aligns versions, it never adds a dependency).
The GPL reaches your app only if *your* build file names this artifact.

## VLC must be on the machine

VLCJ is a binding, not a decoder: the actual work is done by `libvlc`, found at runtime. Either the
user has **VLC 3.x installed** (the standard install location is found automatically on Windows,
macOS and Linux) or your installer **bundles libvlc** and its plugins with the app. The build of VLC
must match the JVM's CPU architecture — an Intel VLC cannot be loaded by an Apple-silicon JVM.
[`05-platform-notes.md`](05-platform-notes.md) has the per-OS details.

A missing or unloadable VLC is never a crash: `isVlcAvailable()` answers the question without
side effects, and a `prepare` without VLC settles on `VideoPlayerState.Error(VlcUnavailableException)`.

## What this is **not**

- **Not a VLC API.** No VLCJ type appears in this artifact's public API, and VLCJ is a runtime-only
  dependency of your app. What VLC can do beyond the shared `VideoPlayer` contract — subtitles,
  track selection, equalizer, recording — is not exposed. Out of scope for v1 on every platform.
- **Not a Swing or AWT component.** Pictures go to memory; `kmptoolkit-video-player-compose` draws
  them. If you need VLC in a Swing app, use VLCJ directly.
- **Not a VLC installer or downloader.** The library finds VLC; it never fetches, installs or
  updates it.
- **Not for Android or iOS.** JVM only. On mobile, the core module's own engines apply.

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — dependency, availability check, first video.
- [`03-guide.md`](03-guide.md) — sources, headers, looping, buffering, lifecycle, failures.
- [`04-api-reference.md`](04-api-reference.md) — every public symbol.
- [`05-platform-notes.md`](05-platform-notes.md) — discovery per OS, bundling libvlc, VLC behaviour
  that differs from Android and iOS.
- [`06-testing.md`](06-testing.md) — testing code that uses this engine, and this module's own tests.
