package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates a desktop [VideoPlayer] backed by VLC through VLCJ.
 *
 * The player renders decoded pictures into memory, which `kmptoolkit-video-player-compose` draws —
 * there is no native window or AWT component involved. Everything else is the shared
 * [VideoPlayer] contract, identical to Android and iOS.
 *
 * Creating the player never touches VLC and never throws when VLC is missing: the native library
 * is located and loaded on the first [VideoPlayer.prepare]. If it cannot be, that prepare settles on
 * [VideoPlayerState.Error] carrying a [VlcUnavailableException]. Call [isVlcAvailable] first when
 * you want to decide up front — for example to show "install VLC" instead of a player.
 *
 * The factory is a plain function, not a DI module — wrap it in whatever you already use.
 *
 * **Licence.** This artifact is MIT, but VLCJ is GPL-3.0 and libvlc is LGPL-2.1: an application
 * that ships this engine must meet the GPL-3.0 (or hold a commercial VLCJ licence). See the
 * module's `01-overview.md`.
 *
 * @param config tunables shared with every platform; see [VideoPlayerConfig].
 * @param coroutineContext context for the position-polling coroutine.
 * @param vlcArgs extra libvlc command-line arguments for the VLC instance this player creates, e.g.
 *   `listOf("--network-caching=3000")`. Empty by default; VLC's own defaults apply.
 * @return a player in [VideoPlayerState.Idle].
 */
public fun createVlcjVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
    vlcArgs: List<String> = emptyList(),
): VideoPlayer = createVideoPlayer(
    engine = VlcjVideoEngine(vlcArgs = vlcArgs.toList()),
    config = config,
    coroutineContext = coroutineContext,
)

/**
 * Whether a usable VLC installation can be found and loaded by this JVM — never throws.
 *
 * It runs VLCJ's native discovery (the standard install locations of each OS, `VLC_PLUGIN_PATH`,
 * `jna.library.path`) and returns `true` only if libvlc actually loaded, so a VLC build for a
 * different CPU architecture than the JVM (an Intel VLC under an Apple-silicon JVM, say) reports
 * `false`. A positive answer is remembered for the life of the process; a negative one is not, so
 * installing VLC while the app runs is picked up by the next call.
 */
public fun isVlcAvailable(): Boolean = VlcNativeDiscovery.discover()
