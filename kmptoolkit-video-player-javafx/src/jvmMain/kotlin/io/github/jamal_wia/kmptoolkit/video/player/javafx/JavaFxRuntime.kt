package io.github.jamal_wia.kmptoolkit.video.player.javafx

/**
 * Makes sure the JavaFX toolkit is running before the engine touches a JavaFX class. A seam so a
 * test can simulate a machine where it cannot run.
 *
 * Deliberately free of JavaFX types: this file must load on a classpath without OpenJFX.
 */
internal fun interface JavaFxRuntime {

    /** Returns once the toolkit runs; throws [JavaFxVideoPlayerException.RuntimeUnavailable]. */
    fun ensureStarted()
}

internal object SystemJavaFxRuntime : JavaFxRuntime {

    private const val MEDIA_PLAYER_CLASS: String = "javafx.scene.media.MediaPlayer"

    private var started: Boolean = false
    private var failure: Throwable? = null

    @Synchronized
    override fun ensureStarted() {
        if (started) return
        failure?.let { throw JavaFxVideoPlayerException.RuntimeUnavailable(it) }
        try {
            Class.forName(MEDIA_PLAYER_CLASS, false, SystemJavaFxRuntime::class.java.classLoader)
            FxThread.startToolkit()
            started = true
        } catch (e: Exception) {
            failure = e
            throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
        } catch (e: LinkageError) {
            // NoClassDefFoundError / UnsatisfiedLinkError: the jars or their native libraries are
            // missing — as permanent as a missing class.
            failure = e
            throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
        }
    }

    fun isAvailable(): Boolean = try {
        ensureStarted()
        true
    } catch (e: JavaFxVideoPlayerException.RuntimeUnavailable) {
        false
    }
}
