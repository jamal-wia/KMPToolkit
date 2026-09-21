package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates a desktop [VideoPlayer] backed by JavaFX Media (`javafx.scene.media.MediaPlayer`).
 *
 * The player renders every picture into memory, so `kmptoolkit-video-player-compose` draws it like
 * any other desktop engine; no JavaFX window or node ever reaches your UI. Creating the player
 * touches no JavaFX class: a missing OpenJFX runtime, or a machine where the JavaFX toolkit cannot
 * start (no display), is reported by [VideoPlayer.prepare] as a `VideoPlayerState.Error` whose cause
 * is [JavaFxVideoPlayerException.RuntimeUnavailable]. Check [isJavaFxMediaAvailable] first to choose
 * an engine up front instead.
 *
 * OpenJFX is **not** a transitive dependency of this artifact: the app adds the OpenJFX runtime jars
 * for each OS it ships to — see the module's getting-started guide.
 *
 * @param config the same tunables as every other [VideoPlayer].
 * @param coroutineContext hosts the position-polling coroutine only.
 */
public fun createJavaFxVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer = createVideoPlayer(JavaFxVideoEngine(), config, coroutineContext)

/**
 * Whether JavaFX Media can play here: the OpenJFX `javafx.media` classes are on the classpath and the
 * JavaFX toolkit is running or could be started. Starts the toolkit as a side effect when it is not
 * running yet (as the first [VideoPlayer.prepare] would), and remembers a failure, so later calls are
 * cheap.
 *
 * `true` does not promise that every source decodes — codec support differs per OS (see the module's
 * platform notes); a source JavaFX cannot decode still fails in [VideoPlayer.prepare].
 */
public fun isJavaFxMediaAvailable(): Boolean = SystemJavaFxRuntime.isAvailable()
