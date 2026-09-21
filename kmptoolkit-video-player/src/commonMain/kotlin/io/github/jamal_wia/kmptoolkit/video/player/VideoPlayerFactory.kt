package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates a [VideoPlayer] over a caller-supplied [engine] — a test fake, or your own backend. The
 * player **takes ownership** of [engine]: it installs itself as the listener and releases the engine
 * from its own [VideoPlayer.release]. One engine per player.
 *
 * @param coroutineContext hosts the position-polling coroutine only; pass a `TestDispatcher` to make
 *   polling deterministic in tests.
 */
public fun createVideoPlayer(
    engine: VideoPlaybackEngine,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer = EngineVideoPlayer(engine, config, coroutineContext)
