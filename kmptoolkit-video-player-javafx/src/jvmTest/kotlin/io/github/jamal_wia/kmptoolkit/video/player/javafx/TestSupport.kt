package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.fail
import org.junit.Assume

/** The checked-in clip: 64×48 solid red, 3 s, H.264 baseline + silent AAC, ~4 KB. */
internal object TestClip {
    const val ASSET: String = "red-64x48-3s.mp4"
    const val WIDTH: Int = 64
    const val HEIGHT: Int = 48
    const val DURATION_MS: Long = 3_000L

    /** A copy on disk, for [io.github.jamal_wia.kmptoolkit.video.player.VideoSource.File]. */
    fun copyToTempFile(): File {
        val file: File = File.createTempFile("kmptoolkit-clip", ".mp4").apply { deleteOnExit() }
        val stream = TestClip::class.java.classLoader.getResourceAsStream(ASSET)
            ?: error("test clip $ASSET missing from the test classpath")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}

/**
 * Skips the calling test where JavaFX cannot run — a headless CI machine, or a JDK the OpenJFX jars
 * do not load on. Everything else about the engine is asserted, never skipped.
 */
internal fun assumeJavaFxMedia() {
    Assume.assumeTrue("JavaFX Media is not available on this machine", isJavaFxMediaAvailable())
}

internal sealed interface EngineEvent {
    data object Completed : EngineEvent
    data class Failed(val cause: Throwable) : EngineEvent
    data class Buffering(val isBuffering: Boolean) : EngineEvent
    data class Size(val size: VideoSize?) : EngineEvent
}

internal class RecordingListener : VideoPlaybackEngineListener {
    val events: MutableList<EngineEvent> = CopyOnWriteArrayList()

    override fun onCompleted() {
        events += EngineEvent.Completed
    }

    override fun onFailed(cause: Throwable) {
        events += EngineEvent.Failed(cause)
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        events += EngineEvent.Buffering(isBuffering)
    }

    override fun onVideoSizeChanged(size: VideoSize?) {
        events += EngineEvent.Size(size)
    }
}

/** Polls [condition] every 20 ms until it holds or [timeoutMs] passes; fails with [message]. */
internal fun awaitTrue(timeoutMs: Long = 5_000L, message: () -> String, condition: () -> Boolean) {
    val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("Timed out after $timeoutMs ms: ${message()}")
        Thread.sleep(20L)
    }
}
