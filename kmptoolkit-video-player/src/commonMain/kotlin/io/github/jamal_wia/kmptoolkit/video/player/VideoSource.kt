package io.github.jamal_wia.kmptoolkit.video.player

/**
 * What a [VideoPlayer] plays. The library never downloads, caches, or deletes a source itself.
 *
 * Every kind is a plain class with value equality rather than a `data class`: a source can gain an
 * optional property in a minor release without breaking a consumer compiled against the previous
 * one (a data class's `copy` and `componentN` would change shape), and [Remote] can keep its request
 * headers out of `toString()`.
 */
public sealed interface VideoSource {

    /**
     * A file bundled with the app, resolved like `kmptoolkit-audio-player`'s `AudioSource.Asset`:
     * Android `assets/`, iOS bundle lookup (see the module's platform notes).
     *
     * @property path path relative to the bundled-resource root, **with extension**.
     */
    public class Asset(public val path: String) : VideoSource {

        override fun equals(other: Any?): Boolean = other is Asset && other.path == path

        override fun hashCode(): Int = path.hashCode()

        override fun toString(): String = "Asset(path=$path)"
    }

    /**
     * A file on the device, read directly.
     *
     * @property path absolute file path.
     */
    public class File(public val path: String) : VideoSource {

        override fun equals(other: Any?): Boolean = other is File && other.path == path

        override fun hashCode(): Int = path.hashCode()

        override fun toString(): String = "File(path=$path)"
    }

    /**
     * A network source, streamed. Progressive files (MP4 and the like) and HLS play on both
     * platforms. Cleartext `http://` is blocked by default on both.
     *
     * `toString()` prints the header **names** only — their values (an `Authorization` token, say)
     * are replaced with `<redacted>`, so a source logged by accident does not leak a credential. The
     * URL is printed as given: if it is signed, it is itself a credential, so do not log it.
     *
     * @property url absolute URL.
     * @property headers extra HTTP request headers sent with every request for this source — an
     *   `Authorization` header for a protected stream, for example. Empty by default. A signed URL
     *   needs none. The map is copied, so changing the one passed in later has no effect.
     * @property format how the stream is packaged. [RemoteFormat.Auto] (the default) lets the
     *   platform work it out; say [RemoteFormat.Hls] for an HLS stream whose URL does not end in
     *   `.m3u8` — a signed or rewritten URL — which Android would otherwise try to play as a single
     *   progressive file and fail.
     */
    public class Remote(
        public val url: String,
        headers: Map<String, String> = emptyMap(),
        public val format: RemoteFormat = RemoteFormat.Auto,
    ) : VideoSource {

        public val headers: Map<String, String> = headers.toMap()

        override fun equals(other: Any?): Boolean =
            other is Remote && other.url == url && other.headers == headers && other.format == format

        override fun hashCode(): Int {
            var result: Int = url.hashCode()
            result = HASH_MULTIPLIER * result + headers.hashCode()
            result = HASH_MULTIPLIER * result + format.hashCode()
            return result
        }

        override fun toString(): String {
            val redacted: String = headers.keys.joinToString(prefix = "{", postfix = "}") { name: String ->
                "$name=$REDACTED"
            }
            return "Remote(url=$url, headers=$redacted, format=$format)"
        }

        private companion object {
            const val HASH_MULTIPLIER: Int = 31
            const val REDACTED: String = "<redacted>"
        }
    }
}

/**
 * How a [VideoSource.Remote] is packaged — a hint for the platform, which otherwise decides from the
 * URL. More formats may be added in a minor release.
 */
public enum class RemoteFormat {

    /**
     * Let the platform decide. Android (Media3) goes by the URL path: `.m3u8` is HLS, anything else a
     * progressive file. iOS (AVFoundation) also reads the server's content type, so it recognises an
     * HLS stream whatever its URL.
     */
    Auto,

    /** A single progressive file (MP4, WebM, …), whatever the URL looks like. */
    Progressive,

    /** An HLS playlist, whatever the URL looks like — the choice for a signed or extension-less HLS URL. */
    Hls,
}
