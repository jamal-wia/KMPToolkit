package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig

/**
 * Creates the [VideoPlayer] that [rememberVideoPlayer] — and the `VideoPlayer(source = …)`
 * composable built on it — hands out. Provide one through [LocalVideoPlayerFactory].
 *
 * On desktop this is how an app picks its playback engine, once, at the root of its UI:
 *
 * ```kotlin
 * CompositionLocalProvider(
 *     LocalVideoPlayerFactory provides VideoPlayerFactory { config -> createVlcjVideoPlayer(config) },
 * ) { App() }
 * ```
 *
 * On Android and iOS you need none: without one, the platform factory is used. Provide one there
 * to pass factory arguments the default does not (an iOS asset bundle, a coroutine context), or to
 * substitute a player over a fake engine in a screenshot or UI test.
 */
public fun interface VideoPlayerFactory {

    /**
     * Creates a new player with [config]. Each call must return a new, unshared instance: the
     * caller owns it and releases it when it leaves the composition.
     */
    public fun create(config: VideoPlayerConfig): VideoPlayer
}

/**
 * The [VideoPlayerFactory] [rememberVideoPlayer] uses; `null` (the default) means the platform one —
 * `createVideoPlayer(LocalContext.current, config)` on Android, `createVideoPlayer(config)` on iOS.
 *
 * **Desktop has no default**: the core module ships no desktop engine, so an app picks one by
 * providing a factory here (see [VideoPlayerFactory]). Without one, [rememberVideoPlayer] throws an
 * [IllegalStateException] that says so.
 */
public val LocalVideoPlayerFactory: ProvidableCompositionLocal<VideoPlayerFactory?> =
    staticCompositionLocalOf { null }

/** The platform's own factory, or `null` where the platform has none (desktop). */
@Composable
internal expect fun platformVideoPlayerFactory(): VideoPlayerFactory?

internal const val MISSING_FACTORY_MESSAGE: String =
    "No VideoPlayerFactory is provided and this platform has no default video engine. On desktop, " +
        "add a desktop engine artifact (kmptoolkit-video-player-vlcj or kmptoolkit-video-player-javafx) " +
        "and provide its factory once at the root of your UI: " +
        "CompositionLocalProvider(LocalVideoPlayerFactory provides VideoPlayerFactory { config -> " +
        "createVlcjVideoPlayer(config) }) { ... }"
