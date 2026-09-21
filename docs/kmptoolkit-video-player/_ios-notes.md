## iOS

**Engine:** `AVFoundation.AVPlayer`. One `AVPlayer` per player for its whole life; each `prepare`
puts a new `AVPlayerItem` (over an `AVURLAsset`) into it, and `release` only empties it. The Compose
surface attaches an `AVPlayerLayer` to that one `AVPlayer` once and never has to re-attach.

**Assets** resolve through `NSBundle.URLForResource(name:withExtension:subdirectory:)`, exactly as
in `kmptoolkit-audio-player`, so the extension in the path matters: `VideoSource.Asset("intro.mp4")`
looks up `intro` with extension `mp4`. The lookup tries the bundle root first, then each entry of
`assetSubdirectories` in order:

```kotlin
// Compose Multiplatform puts resources under compose-resources/
val player: VideoPlayer = createVideoPlayer(assetSubdirectories = listOf("compose-resources"))
```

The default is the main bundle and no subdirectories. Pass `assetBundle` when the video ships inside
a framework's own bundle.

**Remote sources and App Transport Security.** HTTPS needs nothing. Cleartext `http://` is blocked
by ATS, and the failure shows up as `VideoPlayerState.Error` from a failed `AVPlayerItem`, not as a
policy message. If you really must, add an `NSAppTransportSecurity` → `NSExceptionDomains` entry for
that one host to `Info.plist`; prefer fixing the URL. `NSAllowsArbitraryLoadsForMedia` also exists
and is scoped to AVFoundation, but it is still a review-time smell.

**HLS** plays natively — pass the `.m3u8` URL as a `VideoSource.Remote`. No extra dependency, no
configuration. Adaptive bitrate switching is AVFoundation's own.

**HTTP headers** (`VideoSource.Remote.headers`) are passed through the `AVURLAsset` creation option
`"AVURLAssetHTTPHeaderFieldsKey"`. Be aware that **Apple has never documented this key**: it is not
declared in any public header, is used by most iOS video players, and has worked since iOS 7, but
Apple could change it without notice. It applies to the playlist and segment requests AVFoundation
makes for that asset. If you would rather not depend on it, use a signed URL (query-string token)
and pass no headers — a source with no headers creates the asset with no options at all.

**The audio session.** With `managesAudioSession = true` (the default), loading a source sets the
shared `AVAudioSession` to `AVAudioSessionCategoryPlayback` with mode `AVAudioSessionModeMoviePlayback`
and activates it — without that, a video's sound is muted by the ringer switch. The setting is
process-wide; pass `managesAudioSession = false` when the app owns the session (for example because
it also records, or mixes with other audio). Interruptions (a call, Siri, a route change) are not
observed: the player neither pauses nor reports them. The `audio` background mode is not needed —
background playback is out of scope for this module.

**Rendering.** The player itself is headless. `kmptoolkit-video-player-compose` renders it through
an `AVPlayerLayer` bound to the `AVPlayer` returned by the `@ToolkitInternalApi`
`VideoPlayer.avPlayerOrNull()`. That accessor exists for the Compose module; if you render the player
yourself with UIKit, it is the hook to use, with the understanding that it carries no compatibility
promise. AVPlayer keeps the display awake while a video plays by default
(`preventsDisplaySleepDuringVideoPlayback`).

**Main thread.** Every AVFoundation call the engine makes happens on the main thread: `prepare` hops
there, and transport calls run inline when made on the main thread (the normal case) and are
dispatched to it otherwise. `avPlayerOrNull()` must be called on the main thread. The polled values
(position, duration, buffered position) are read from values refreshed on the main thread, so the
position-polling coroutine may run anywhere.

**How state is observed.** By polling on the main thread every 50 ms while a source is loaded — not
by key-value observing, which has no Kotlin/Native binding that is safe against observing a
deallocated object (the same choice `kmptoolkit-audio-player` makes). Each tick reads:

- `AVPlayerItem.status` — a mid-playback `Failed` becomes `VideoPlayerState.Error`;
- `AVPlayer.timeControlStatus` — `WaitingToPlayAtSpecifiedRate` is reported as buffering, except
  with the "evaluating buffering rate" reason AVPlayer passes through for a few milliseconds on every
  start, which would otherwise flash a spinner even for a local file;
- `AVPlayerItem.presentationSize` — the picture size, `null` while it is zero (not yet known, or an
  audio-only source);
- `AVPlayerItem.loadedTimeRanges` — the buffered position is the end of the loaded range the
  playhead is in.

End of playback comes from `AVPlayerItemDidPlayToEndTimeNotification` and mid-playback failure also
from `AVPlayerItemFailedToPlayToEndTimeNotification`. Loading polls `AVPlayerItem.status` every 20 ms
until `ReadyToPlay` or `Failed`, which is also what makes `prepare` cancellable: cancelling it
cancels the asset's loading and leaves the player empty.

**Repeat mode.** `RepeatMode.One` is implemented by seeking back to zero and playing again when the
item reaches its end; completion is not reported while it is on. There can be a frame-or-two gap at
the loop point — `AVPlayerLooper` would be gapless but needs an `AVQueuePlayer` and item templates,
which does not fit a player that swaps its source on every `prepare`.

**Seeking** is frame-accurate (zero tolerance before and after). That is what a seek bar and a
"watched 95 %" check want; on a long remote stream it can take slightly longer than a keyframe seek.

**Playback speed** is assigned to `AVPlayer.rate` right after `play()` and immediately while playing;
setting a speed while paused does not start playback.

**Errors.** A load failure is an `IllegalStateException` whose message carries the `NSError` domain,
code and `localizedDescription` of the failed `AVPlayerItem`; an asset missing from the bundle, a
blank file path or a URL `NSURL` refuses is an `IllegalArgumentException`.
