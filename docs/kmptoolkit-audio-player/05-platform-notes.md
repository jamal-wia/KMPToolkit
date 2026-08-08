# kmptoolkit-audio-player — Platform notes

What differs between Android and iOS, and what the consuming app has to declare.

## Permissions — the library declares none

`kmptoolkit-audio-player`'s `AndroidManifest.xml` contains a `namespace` and nothing else. No
permission is manifest-merged into your app by adding this dependency, deliberately: a permission
that appears in a consumer's manifest without them asking is a permission they have to justify in a
Play Store listing.

Local playback (`Asset`, `File`) needs no permission at all on either platform.

| What you want to do | Android | iOS |
|---|---|---|
| Play a bundled asset or a local file | nothing | nothing |
| Stream over HTTPS | `android.permission.INTERNET` | nothing |
| Stream over cleartext `http://` | `INTERNET` + a network-security config (below) | an `NSAppTransportSecurity` exception |
| Keep playing with the screen off | a foreground service — out of scope, see [`01-overview.md`](01-overview.md) | the `audio` background mode |

`INTERNET` is a normal (install-time) permission — declare it in your own manifest, no runtime
request needed:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Nothing here records, so `RECORD_AUDIO` and `NSMicrophoneUsageDescription` are not involved.

## Cleartext HTTP

Both platforms block plain `http://` by default, and the failure looks like a load error rather than
a policy message: `PlayerState.Error` with an `IOException` on Android, a failed `AVPlayerItem` on
iOS.

Android — declare the host in a network-security config:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">audio.internal.example</domain>
    </domain-config>
</network-security-config>
```

iOS — an `NSAppTransportSecurity` exception in `Info.plist`, scoped to the host.

Prefer fixing the URL. Both platforms treat a blanket cleartext opt-in as a review-time smell.

## Android

**Engine:** `android.media.MediaPlayer`. Not Media3/ExoPlayer — see
[`01-overview.md`](01-overview.md#what-this-is-not).

**Assets** resolve through `AssetManager.openFd(path)`, so `AudioSource.Asset("sounds/chime.mp3")`
means `src/main/assets/sounds/chime.mp3`. The file must be stored uncompressed for a file descriptor
to be openable — audio formats already are, so this only bites for something like a `.wav` inside a
zip.

**Threading.** The `MediaPlayer` is created on `Dispatchers.IO`, which does blocking `setDataSource`
work off the main thread and — because that thread has no `Looper` — makes the platform deliver its
callbacks on the main looper. That is why the module needs no main dispatcher and therefore no
`kotlinx-coroutines-android` dependency.

**Playback speed** is applied via `PlaybackParams`, available since API 23. The module's `minSdk` is
24, so there is no fallback path. The rate is pushed only while playing: assigning `playbackParams`
to a paused `MediaPlayer` starts playback on several API levels.

**Audio focus is not requested.** Playing while another app plays music results in both being
audible. Request focus with `AudioManager` in the app if you need ducking or pausing — it is a
policy decision about the whole app, not about one player.

**Errors** arrive as `IOException` carrying the platform's `what`/`extra` codes; `MediaPlayer`
exposes no richer detail.

## iOS

**Engine:** `AVFoundation.AVPlayer`, one `AVPlayerItem` per prepared source.

**Assets** resolve through `NSBundle.URLForResource(name:withExtension:subdirectory:)`, so the
extension in the path matters — `AudioSource.Asset("chime.mp3")` looks up `chime` with extension
`mp3`. The lookup tries the bundle root first, then each entry of `assetSubdirectories` in order:

```kotlin
// Compose Multiplatform puts resources under compose-resources/
val player: AudioPlayer = createAudioPlayer(assetSubdirectories = listOf("compose-resources"))
```

The default is the main bundle and no subdirectories. The donor implementation hardcoded the
`compose-resources` fallback; this library will not guess at a resource layout on your behalf.

**The audio session** is the one piece of process-wide state involved. With
`managesAudioSession = true` (the default), loading a source sets the shared `AVAudioSession` to
`AVAudioSessionCategoryPlayback` and activates it. That is what makes audio play with the ringer
switch silenced and continue at screen lock, and without it most callers would report the player as
"silently broken".

Pass `managesAudioSession = false` when the app owns its session — in particular when it also
records, where the category has to be `PlayAndRecord` and switching it under the recorder's feet
breaks it:

```kotlin
val player: AudioPlayer = createAudioPlayer(managesAudioSession = false)
```

**Interruptions are not handled.** A phone call, a Siri invocation, or a route change (headphones
unplugged) is not observed: the player does not pause, resume, or report anything. An app that cares
observes `AVAudioSession.interruptionNotification` itself and calls `pause()`/`play()`.

**Background playback** additionally needs the `audio` background mode in `Info.plist`. The library
does not set it, and setting it without genuinely playing in the background is an App Store
rejection.

**Loading progress** is detected by polling `AVPlayerItem.status` every 20 ms until it is
`ReadyToPlay` or `Failed`. Polling rather than KVO: key-value observing has no Kotlin/Native binding
that is safe against observing a deallocated object, and the loop runs only during loading — which
also makes `prepare()`'s cancellation contract hold on iOS.

**Errors** are `IllegalStateException` carrying the `AVPlayerItem`'s `localizedDescription`, or
`IllegalArgumentException` for an asset that is not in the bundle or a URL `NSURL` refuses.

## Behavior that is identical on both

- The state machine, every transition, and the clamping rules — they live in `commonMain`.
- The release contract, including double release and use-after-release.
- Position polling cadence and the fact that it stops outside `Playing`.
- Playback-speed clamping, and applying the rate only while playing.
