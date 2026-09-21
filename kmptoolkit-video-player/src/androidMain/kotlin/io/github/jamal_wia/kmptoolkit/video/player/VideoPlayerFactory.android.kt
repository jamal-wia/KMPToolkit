package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates a [VideoPlayer] backed by Media3 ExoPlayer. Only `context.applicationContext` is retained.
 */
public fun createVideoPlayer(
    context: Context,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer = TODO("Media3 engine — implemented by the core agent")

/**
 * The Media3 player behind a player this module created, for `kmptoolkit-video-player-compose` to
 * attach a surface to; `null` for a player over any other engine. Main thread only.
 */
@ToolkitInternalApi
public fun VideoPlayer.media3PlayerOrNull(): androidx.media3.common.Player? =
    TODO("implemented by the core agent")
