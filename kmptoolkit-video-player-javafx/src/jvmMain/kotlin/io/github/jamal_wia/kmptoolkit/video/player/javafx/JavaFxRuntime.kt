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

/**
 * Starts the real toolkit through [startToolkit]. Only a permanent failure is remembered: a missing
 * class or native library, a toolkit that has exited, a machine with no display. An
 * [FxThreadUnresponsiveException] describes a busy FX thread, which may pass, so the next call tries
 * again.
 *
 * @param startToolkit a test seam. Its default is a lambda, not a reference to `FxThread`: this file
 * must load on a classpath without OpenJFX.
 */
internal class ToolkitJavaFxRuntime(
    private val startToolkit: () -> Unit = { FxThread.startToolkit() },
) : JavaFxRuntime {

    private var started: Boolean = false
    private var failure: Throwable? = null

    // Blocking by nature: the start waits on the FX thread, and callers are plain synchronous code.
    @Synchronized
    override fun ensureStarted() {
        if (started) return
        failure?.let { throw JavaFxVideoPlayerException.RuntimeUnavailable(it) }
        try {
            Class.forName(MEDIA_PLAYER_CLASS, false, ToolkitJavaFxRuntime::class.java.classLoader)
            startToolkit()
            started = true
        } catch (e: FxThreadUnresponsiveException) {
            throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
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

    private companion object {
        const val MEDIA_PLAYER_CLASS: String = "javafx.scene.media.MediaPlayer"
    }
}

/** The process-wide runtime: one remembered outcome for every engine. */
internal object SystemJavaFxRuntime : JavaFxRuntime {

    private val delegate: ToolkitJavaFxRuntime = ToolkitJavaFxRuntime()

    override fun ensureStarted(): Unit = delegate.ensureStarted()

    fun isAvailable(): Boolean = delegate.isAvailable()
}
