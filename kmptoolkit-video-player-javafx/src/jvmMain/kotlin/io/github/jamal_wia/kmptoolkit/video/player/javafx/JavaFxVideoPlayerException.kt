package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource

/**
 * The failures specific to the JavaFX engine. The engine throws them while loading, and a
 * [VideoPlayer] reports them the way it reports any load failure, as the [VideoPlayerState.Error.cause]
 * after [VideoPlayer.prepare]. Everything else arrives as the platform reported it: a
 * `javafx.scene.media.MediaException` for a source JavaFX cannot open or decode, a
 * `java.io.FileNotFoundException` for a [VideoSource.File] or [VideoSource.Asset] that does not
 * exist, an `IllegalArgumentException` for a blank path or URL. A failure after loading succeeded
 * arrives the same way, as the `MediaException` JavaFX reported.
 *
 * The messages are diagnostics for a developer, not text to show a user.
 */
public sealed class JavaFxVideoPlayerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The OpenJFX runtime is missing from the classpath, or the JavaFX toolkit cannot start here —
     * typically a machine with no display. [cause] is what JavaFX reported. The failure is
     * remembered for the life of the process.
     */
    public class RuntimeUnavailable internal constructor(
        cause: Throwable,
    ) : JavaFxVideoPlayerException("JavaFX Media is not available: $cause", cause)

    /**
     * JavaFX reported the source neither ready nor failed within [timeoutMs]. JavaFX Media does this
     * for some sources it cannot play — on macOS, a file that is not a media container it can parse —
     * instead of reporting an error; this turns that silence into a failure.
     *
     * @property timeoutMs how long the engine waited.
     */
    public class LoadTimedOut internal constructor(
        public val timeoutMs: Long,
    ) : JavaFxVideoPlayerException(
        "JavaFX Media reported the source neither ready nor failed within $timeoutMs ms",
    )

    /**
     * The [VideoSource.Remote] carried [VideoSource.Remote.headers], which JavaFX Media has no way to
     * send. Rejected rather than dropped: a stream that needs an `Authorization` header would
     * otherwise fail later with an opaque HTTP error, or worse, play the wrong content. Use a signed
     * URL instead.
     *
     * @property headerNames the names of the headers that were supplied.
     */
    public class HeadersNotSupported internal constructor(
        public val headerNames: Set<String>,
    ) : JavaFxVideoPlayerException(
        "JavaFX Media cannot send HTTP request headers; got $headerNames",
    )
}
