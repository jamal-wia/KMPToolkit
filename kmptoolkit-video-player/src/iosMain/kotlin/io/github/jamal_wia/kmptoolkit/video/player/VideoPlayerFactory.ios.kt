package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import platform.AVFoundation.AVPlayer
import platform.Foundation.NSBundle

/**
 * Creates a [VideoPlayer] backed by `AVPlayer`.
 *
 * @param assetBundle / [assetSubdirectories] where [VideoSource.Asset] is looked up: the bundle root
 *   first, then each subdirectory in order (Compose Multiplatform apps pass `listOf("compose-resources")`).
 * @param managesAudioSession whether to switch the shared `AVAudioSession` to the playback category.
 */
public fun createVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    assetBundle: NSBundle = NSBundle.mainBundle,
    assetSubdirectories: List<String> = emptyList(),
    managesAudioSession: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer = createVideoPlayer(
    engine = AvPlayerVideoEngine(
        assetBundle = assetBundle,
        assetSubdirectories = assetSubdirectories,
        managesAudioSession = managesAudioSession,
    ),
    config = config,
    coroutineContext = coroutineContext,
)

/**
 * The `AVPlayer` behind a player this module created, for `kmptoolkit-video-player-compose` to
 * attach an `AVPlayerLayer` to; `null` for a player over any other engine. Main thread only.
 *
 * The instance is stable for the player's whole life — created on first access (so a layer can be
 * attached before the first `prepare`), kept across sources, and merely emptied by `release` — so
 * the layer needs attaching once.
 */
@ToolkitInternalApi
public fun VideoPlayer.avPlayerOrNull(): AVPlayer? =
    ((this as? EngineVideoPlayer)?.engine as? AvPlayerVideoEngine)?.player
