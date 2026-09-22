package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.common.Player
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates a [VideoPlayer] backed by Media3 ExoPlayer.
 *
 * A plain function, not a DI module: wrap the call in whatever container you already use. Only
 * `context.applicationContext` is retained, so a player held longer than a screen cannot leak an
 * `Activity`. The player must still be released — see the lifecycle contract on [VideoPlayer].
 *
 * Callable from any thread. ExoPlayer itself lives on the main thread — the engine creates it there
 * and marshals every call onto it — so the thread a view model drives the player from does not
 * matter, and nothing blocks waiting for the main thread.
 *
 * Streaming a [VideoSource.Remote] needs the `INTERNET` permission, which this library does not
 * declare: add it to your app's manifest (see the module's platform notes).
 *
 * @param context any context; its application context is what gets stored.
 * @param config tunables; see [VideoPlayerConfig].
 * @param coroutineContext hosts the position-polling coroutine.
 * @return a player in [VideoPlayerState.Idle].
 */
public fun createVideoPlayer(
    context: Context,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer = createVideoPlayer(
    engine = Media3VideoEngine(context.applicationContext),
    config = config,
    coroutineContext = coroutineContext,
)

/**
 * The Media3 player behind a player this module created, for `kmptoolkit-video-player-compose` to
 * attach a surface to; `null` for a player over any other engine, and after [VideoPlayer.release].
 *
 * Main thread only. The same instance is returned for the player's whole life — across
 * [VideoPlayer.unload] and every [VideoPlayer.prepare] — so a surface attaches once. Called on the
 * main thread before the first [VideoPlayer.prepare], it creates the ExoPlayer then.
 */
@ToolkitInternalApi
public fun VideoPlayer.media3PlayerOrNull(): Player? =
    ((this as? EngineVideoPlayer)?.engine as? Media3VideoEngine)?.playerForSurface()
