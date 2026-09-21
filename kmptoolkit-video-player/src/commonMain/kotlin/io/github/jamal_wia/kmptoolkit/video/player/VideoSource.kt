package io.github.jamal_wia.kmptoolkit.video.player

/** What a [VideoPlayer] plays. The library never downloads, caches, or deletes a source itself. */
public sealed interface VideoSource {

    /**
     * A file bundled with the app, resolved like `kmptoolkit-audio-player`'s `AudioSource.Asset`:
     * Android `assets/`, iOS bundle lookup (see the module's platform notes).
     *
     * @property path path relative to the bundled-resource root, **with extension**.
     */
    public data class Asset(val path: String) : VideoSource

    /**
     * A file on the device, read directly.
     *
     * @property path absolute file path.
     */
    public data class File(val path: String) : VideoSource

    /**
     * A network source, streamed. Progressive files (MP4 and the like) and HLS play on both
     * platforms. Cleartext `http://` is blocked by default on both.
     *
     * @property url absolute URL.
     * @property headers extra HTTP request headers sent with every request for this source — an
     *   `Authorization` header for a protected stream, for example. Empty by default. A signed URL
     *   needs none.
     */
    public data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : VideoSource
}
