package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Creates an [AudioPlayer] backed by [android.media.MediaPlayer].
 *
 * The factory is a plain function, not a DI module: this library binds nothing into a container, so
 * wrap the call in whatever you already use (`single { createAudioPlayer(get()) }` in Koin, an
 * `@Provides` in Hilt, a field in a hand-wired graph).
 *
 * Only the *application* context is retained, so a player held longer than the screen that created
 * it cannot leak an `Activity`. The player itself still must be released — see the lifecycle
 * contract on [AudioPlayer].
 *
 * @param context any context; its application context is what gets stored.
 * @param config tunables; see [AudioPlayerConfig].
 * @param coroutineContext context for the position-polling coroutine.
 * @return a player in [PlayerState.Idle].
 */
public fun createAudioPlayer(
    context: Context,
    config: AudioPlayerConfig = AudioPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer = createAudioPlayer(
    engine = MediaPlayerEngine(context.applicationContext),
    config = config,
    coroutineContext = coroutineContext,
)
