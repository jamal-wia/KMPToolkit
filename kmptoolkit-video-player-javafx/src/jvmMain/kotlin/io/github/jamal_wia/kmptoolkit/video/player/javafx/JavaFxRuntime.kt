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
 * @param startToolkit a test seam.
 * @param onFxThread whether the caller is the FX application thread; a test seam. Its default is a lambda, not a reference to `FxThread`: this file
 * must load on a classpath without OpenJFX.
 */
internal class ToolkitJavaFxRuntime(
    private val startToolkit: () -> Unit = { FxThread.startToolkit() },
    private val onFxThread: () -> Boolean = { FxThread.isCurrent() },
) : JavaFxRuntime {

    // Volatile (started, failure): read on the lock-free fast path (which the FX thread must take
    // without the monitor), written under it.
    @Volatile
    private var started: Boolean = false
    @Volatile
    private var failure: Throwable? = null

    override fun ensureStarted() {
        if (started) return
        // A remembered failure answers at once: the thread check below can rebuild the toolkit
        // (and print its stack trace) on every call, and an unavailable runtime has no use for it.
        failure?.let { throw JavaFxVideoPlayerException.RuntimeUnavailable(it) }
        // Before the monitor: another thread may hold it while it waits for the FX thread, which
        // would then wait for the monitor in turn. Being on the FX thread proves the toolkit runs.
        if (isFxThread()) {
            started = true
            return
        }
        ensureStartedLocked()
    }

    // Asking JavaFX who the caller is can itself build the toolkit (`Toolkit.getToolkit()`), and a
    // broken runtime fails there — "No toolkit found", a missing native library. That is not an
    // answer to this question and must not escape: "not the FX thread" sends the caller on to the
    // locked path, which meets the same failure inside its try and remembers it.
    private fun isFxThread(): Boolean = try {
        onFxThread()
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        false
    }

    // Blocking by nature: the start waits on the FX thread, and callers are plain synchronous code.
    @Synchronized
    private fun ensureStartedLocked() {
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
