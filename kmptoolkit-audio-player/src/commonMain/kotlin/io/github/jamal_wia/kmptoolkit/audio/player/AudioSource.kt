package io.github.jamal_wia.kmptoolkit.audio.player

/**
 * What an [AudioPlayer] should load.
 *
 * The three cases exist because the three platforms resolve them through different APIs — a bundled
 * resource is not a file path, and a remote URL is streamed rather than read. Passing a URL string
 * as an [File] path (or the reverse) fails at load time with a platform error surfaced as
 * [PlayerState.Error], so pick the case that matches where the bytes actually live.
 */
public sealed interface AudioSource {

    /**
     * Audio shipped inside the app package: Android `assets/`, iOS bundle resources.
     *
     * @property path path relative to the platform's bundled-resource root, including the file
     *   extension (`"sounds/chime.mp3"`). On iOS the extension is required — it is what the bundle
     *   lookup matches on. See `docs/kmptoolkit-audio-player/05-platform-notes.md` for how each
     *   platform resolves it.
     */
    public data class Asset(val path: String) : AudioSource

    /**
     * Audio already written to device storage.
     *
     * @property path absolute file path. The library never creates, downloads, or cleans up this
     *   file — see the "What this is not" section of `01-overview.md`.
     */
    public data class File(val path: String) : AudioSource

    /**
     * Audio streamed from a URL.
     *
     * @property url absolute URL. Cleartext `http://` is blocked by default on both platforms;
     *   `05-platform-notes.md` states what the consuming app has to declare to allow it.
     */
    public data class Remote(val url: String) : AudioSource
}
