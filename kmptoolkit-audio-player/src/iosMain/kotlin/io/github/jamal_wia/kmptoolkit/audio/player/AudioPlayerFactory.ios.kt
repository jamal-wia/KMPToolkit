package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSBundle
import kotlin.coroutines.CoroutineContext

/**
 * Creates an [AudioPlayer] backed by `AVFoundation.AVPlayer`.
 *
 * The two bundle parameters exist so this library hardcodes no identifier belonging to the app
 * embedding it. The donor implementation looked up assets in the main bundle and then, as a
 * fallback, in a folder literally named `compose-resources` — a Compose Multiplatform convention
 * baked into a module that has nothing to do with Compose, and invisible to anyone not using it. The
 * default here searches the main bundle only; a consumer whose audio lives elsewhere names that
 * place themselves.
 *
 * @param config tunables; see [AudioPlayerConfig].
 * @param assetBundle bundle searched for [AudioSource.Asset]. Defaults to the app's main bundle;
 *   pass your framework's own bundle when the audio ships inside a dynamic framework.
 * @param assetSubdirectories bundle subdirectories searched, in order, after the bundle root — for
 *   example `listOf("compose-resources")` for a Compose Multiplatform app, or `listOf("sounds")` for
 *   a resource folder copied with its directory structure preserved.
 * @param managesAudioSession whether the player switches the shared `AVAudioSession` to the
 *   playback category and activates it while loading. Left `true` because without it playback is
 *   silent under the ringer switch and stops at screen lock, which is almost never what a caller
 *   wants. Set it to `false` when the app configures the session itself — that setting is
 *   process-wide, and an app mixing recording with playback must own it. See
 *   `docs/kmptoolkit-audio-player/05-platform-notes.md`.
 * @param coroutineContext context for the position-polling coroutine.
 * @return a player in [PlayerState.Idle].
 */
public fun createAudioPlayer(
    config: AudioPlayerConfig = AudioPlayerConfig(),
    assetBundle: NSBundle = NSBundle.mainBundle,
    assetSubdirectories: List<String> = emptyList(),
    managesAudioSession: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer = createAudioPlayer(
    engine = AvPlayerEngine(
        assetBundle = assetBundle,
        assetSubdirectories = assetSubdirectories,
        managesAudioSession = managesAudioSession,
    ),
    config = config,
    coroutineContext = coroutineContext,
)
