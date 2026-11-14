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
 * again; so does an interrupt of the calling thread, which is the caller's cancellation and not a
 * property of this machine (the caller keeps its interrupt status). A toolkit seen running is re-checked for an exit on every call, so a load after
 * `Platform.exit()` fails at once.
 *
 * @param startToolkit a test seam.
 * @param onFxThread whether the caller is the FX application thread; a test seam. Its default is a lambda, not a reference to `FxThread`: this file
 * must load on a classpath without OpenJFX.
 * @param hasExited whether a toolkit already seen running has exited; a test seam, a lambda for the
 * same reason as [onFxThread].
 */
internal class ToolkitJavaFxRuntime(
    private val startToolkit: () -> Unit = { FxThread.startToolkit() },
    private val onFxThread: () -> Boolean = { FxThread.isCurrent() },
    private val hasExited: () -> Boolean = { FxThread.hasExited() },
) : JavaFxRuntime {

    // Volatile (started, failure): read on the lock-free fast path (which the FX thread must take
    // without the monitor), written under it.
    @Volatile
    private var started: Boolean = false
    @Volatile
    private var failure: Throwable? = null

    override fun ensureStarted() {
        if (started) {
            checkNotExited()
            return
        }
        // A remembered failure answers at once: the thread check below can rebuild the toolkit
        // (and print its stack trace) on every call, and an unavailable runtime has no use for it.
        failure?.let { throw JavaFxVideoPlayerException.RuntimeUnavailable(it) }
        // Before the monitor: another thread may hold it while it waits for the FX thread, which
        // would then wait for the monitor in turn. Being on the FX thread proves the toolkit runs.
        if (isFxThread()) {
            // Being on the FX thread proves javafx.graphics runs, not that javafx.media is present.
            // `failure` is written here without the monitor (the FX thread must not take it): it is a
            // volatile and every writer stores a permanent failure, so concurrent writes are equivalent
            // (D-17/D-21).
            try {
                mediaClassesPresent()
            } catch (e: ClassNotFoundException) {
                failure = e
                throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
            } catch (e: LinkageError) {
                failure = e
                throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
            }
            started = true
            return
        }
        ensureStartedLocked()
    }

    // Deliberately not remembered in `failure`: the probe is cheap, and remembering the exit would add a
    // fast-path write for no gain.
    private fun checkNotExited() {
        if (toolkitExited()) {
            throw JavaFxVideoPlayerException.RuntimeUnavailable(IllegalStateException("The JavaFX toolkit has exited"))
        }
    }

    // Like isFxThread, a probe that cannot be answered is not an exit.
    private fun toolkitExited(): Boolean = try {
        hasExited()
    } catch (e: InterruptedException) {
        // The probe waits on a latch. An interrupted probe proves nothing: not an exit, and the caller keeps its status.
        Thread.currentThread().interrupt()
        false
    } catch (e: Exception) {
        false
    } catch (e: LinkageError) {
        false
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
        if (started) {
            checkNotExited()
            return
        }
        failure?.let { throw JavaFxVideoPlayerException.RuntimeUnavailable(it) }
        try {
            mediaClassesPresent()
            startToolkit()
            started = true
        } catch (e: FxThreadUnresponsiveException) {
            throw JavaFxVideoPlayerException.RuntimeUnavailable(e)
        } catch (e: InterruptedException) {
            // The caller was interrupted while the first start waited for the FX thread: its cancellation, not a property
            // of this machine. Not remembered (the next call tries again), and the caller keeps its interrupt status.
            Thread.currentThread().interrupt()
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

    /** Throws when the `javafx.media` classes are not on the classpath. */
    private fun mediaClassesPresent() {
        Class.forName(MEDIA_PLAYER_CLASS, false, ToolkitJavaFxRuntime::class.java.classLoader)
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
