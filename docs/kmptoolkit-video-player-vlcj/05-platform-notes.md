# kmptoolkit-video-player-vlcj — Platform notes

JVM only: Windows, macOS and Linux desktops. This page covers how VLC is found, how to ship it, and
where VLC behaves differently from the Android and iOS engines.

## How VLC is found

On the first `prepare` (or `isVlcAvailable()`), VLCJ's native discovery looks for `libvlc` and, once
it finds one, **loads it** — only a successful load counts. The places it checks:

1. the `jna.library.path` system property;
2. a `vlcj.config` file holding `nativeDirectory=<dir>` (VLCJ reads it from the working directory
   and from the user's home configuration directory — see VLCJ's documentation);
3. directories on `PATH`;
4. the standard install locations of the OS:

| OS | Where |
|---|---|
| Windows | The `InstallDir` from the registry key `HKLM\SOFTWARE\VideoLAN\VLC` (written by the official installer). |
| macOS | `/Applications/VLC.app/Contents/MacOS/lib` (VLC 3) and `/Applications/VLC.app/Contents/Frameworks`; plugins from `../plugins` next to the library. |
| Linux | `/usr/lib/x86_64-linux-gnu`, `/usr/lib64`, `/usr/local/lib64`, `/usr/lib/i386-linux-gnu`, `/usr/lib`, `/usr/local/lib`. |

The result is process-wide: a success is remembered (libvlc cannot be unloaded from a JVM), a
failure is retried by the next call.

### The CPU architecture must match

A 64-bit ARM JVM can only load an ARM `libvlc`, an x86-64 JVM only an x86-64 one. The common trap:

- **macOS on Apple silicon** with the Intel build of VLC installed (or one migrated from an Intel
  Mac) and an arm64 JDK — VLC is found but cannot be loaded, so `isVlcAvailable()` is `false`.
  Install the *Apple Silicon* or *Universal* VLC build, or run an x86-64 JDK under Rosetta.
- **Windows** with 32-bit VLC and a 64-bit JDK (or the reverse).
- **Linux on ARM**: `/usr/lib/aarch64-linux-gnu` is not among the well-known directories; point
  `jna.library.path` at it.

### Per-OS notes

- **Windows.** The official installer is found through the registry. The portable (zip) VLC is not
  — use `jna.library.path` or `vlcj.config`.
- **macOS.** Only VLC in `/Applications` is found automatically; a VLC in `~/Applications` or
  elsewhere needs `jna.library.path`. VLC from Homebrew's cask installs into `/Applications` and is
  found.
- **Linux.** Install VLC from the distribution's packages (`vlc`, or `libvlc5` + `libvlccore9` +
  plugin packages), which place libvlc in a well-known directory. **Snap and Flatpak VLC are not
  usable** — their libraries live inside a sandbox the JVM cannot load from.

## Shipping VLC with your app

Relying on the user's VLC is the simplest option: detect with `isVlcAvailable()` and send them to
videolan.org. To ship it yourself:

1. Include `libvlc`, `libvlccore` and the `plugins/` directory of a VLC 3.x build for each OS and CPU
   architecture you ship.
2. Point discovery at them from your launcher, e.g. in a Compose Desktop build:

   ```kotlin
   compose.desktop.application {
       jvmArgs += listOf("-Djna.library.path=\$APPDIR/vlc")
   }
   ```

   If the plugins are not in VLC's default place relative to the library, set the `VLC_PLUGIN_PATH`
   environment variable for the process as well.
3. On macOS, every bundled `.dylib`, plugins included, must be signed with your identity for the app
   to pass notarization.

Bundling VLC means **you distribute VLC**, so its licences (LGPL-2.1+ for libvlc and most modules,
GPL-2.0+ for some) apply to your distribution in addition to VLCJ's GPL-3.0 — see
[`01-overview.md`](01-overview.md).

## Permissions

None. Desktop JVMs have no permission model this module would need to declare. macOS may ask the
user for access to protected folders (Desktop, Documents, removable volumes) the first time a
`VideoSource.File` there is opened — that prompt comes from the OS, not from this library.

## Behaviour that differs from Android and iOS

| Area | Android / iOS | VLC engine |
|---|---|---|
| First frame | Shown once prepared | Nothing decoded before the first `play()` |
| Remote headers | Any header | `User-Agent` and `Referer` only; any other fails the prepare with `IllegalArgumentException` |
| `bufferedPositionFlow` | How far the platform buffered | Duration (local) / playhead (remote) — VLC does not report it |
| `RepeatMode.One` | Restarted by the platform player | The input re-opens at the end; a short gap is possible |
| Volume above `1f` | Not possible | Not done either — VLC's amplification (up to 200 %) is never used |
| Failure detail | Platform exception | `VlcPlaybackException` without detail; VLC's log (`-vvv`) has it |
| Assets | App bundle | Classpath resources; copied to a temporary file when packed in a jar |
| Cleartext `http://` | Blocked by default | Allowed — desktop has no such policy |

## Rendering

The engine asks VLC for 32-bit RGB frames of the decoded picture size and copies each one into an
ARGB `IntArray` that `kmptoolkit-video-player-compose` draws. The copy is CPU work proportional to
the picture: negligible for typical web video, noticeable for 4K at 60 fps on slow machines. Scaling
to the surface happens in Compose, so VLC never renders larger than the source.

Every frame gets a **fresh array**. The frame flow is conflated and drawn on another thread, which may
hold a frame for as long as it likes, so a reused buffer could be overwritten while it is being drawn
(a torn picture). The cost is short-lived garbage — about 8 MB per 1080p frame, collected in the young
generation (the JVM's default G1 collector reclaims such large arrays eagerly); only the frames still
referenced are in memory at any time.

### Picture size and anamorphic video

VLC hands pictures over at their **stored** size, without applying the sample aspect ratio. For an
anamorphic source — DVD video stored at 720×480 but shown at 16:9, HDV stored at 1440×1080 — that
size is not the shape of the picture. `videoSizeFlow` therefore reports the **displayed** size read
from the parsed track (sample aspect ratio applied, rotation honoured), and the surface stretches each
stored frame to it. Only when parsing found no size, as for some network streams, does
`videoSizeFlow` fall back to the stored size of the decoded pictures.
