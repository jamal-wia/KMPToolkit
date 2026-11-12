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

    /**
     * The audio-only clip (1 s of silence, no video track) for tests that churn many players: a player
     * without a video track never gets a CoreVideo display link, so it cannot hit the OpenJFX macOS
     * dispose race (see `plans/javafx-native-crash-advice.md`).
     */
    const val AUDIO_ASSET: String = "silence-8khz-1s.wav"

    /** A copy on disk, for [io.github.jamal_wia.kmptoolkit.video.player.VideoSource.File]. */
    fun copyToTempFile(): File = copyAssetToTempFile(ASSET, "kmptoolkit-clip", ".mp4")

    /** A copy of [AUDIO_ASSET] on disk. */
    fun copyAudioToTempFile(): File = copyAssetToTempFile(AUDIO_ASSET, "kmptoolkit-audio", ".wav")

    private fun copyAssetToTempFile(asset: String, prefix: String, suffix: String): File {
        val file: File = File.createTempFile(prefix, suffix).apply { deleteOnExit() }
        val stream = TestClip::class.java.classLoader.getResourceAsStream(asset)
            ?: error("test clip $asset missing from the test classpath")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}

/**
 * Skips the calling test where JavaFX cannot run — a headless CI machine, or a JDK the OpenJFX jars
 * do not load on. Everything else about the engine is asserted, never skipped.
 *
 * With `-Pjavafx.required=true` (forwarded as the [JAVAFX_REQUIRED_PROPERTY] system property) the
 * skip becomes a failure, so a machine that is meant to run the real-engine tests cannot silently
 * skip all of them.
 */
internal fun assumeJavaFxMedia() {
    val available: Boolean = isJavaFxMediaAvailable()
    if (!available && System.getProperty(JAVAFX_REQUIRED_PROPERTY).toBoolean()) {
        fail("JavaFX Media is not available on this machine, and $JAVAFX_REQUIRED_PROPERTY is set")
    }
    Assume.assumeTrue("JavaFX Media is not available on this machine", available)
}

internal const val JAVAFX_REQUIRED_PROPERTY: String = "kmptoolkit.javafx.required"

/** Directory the build points the JVM crash logs at (the system property is set by the module's test task). */
internal const val HS_ERR_DIR_PROPERTY: String = "kmptoolkit.test.hsErrDir"

/**
 * JVM flags for a child JVM a test starts, mirroring the test task's own: a native crash inside
 * OpenJFX's macOS media backend then ends the child with SIGKILL (no macOS crash dialog) and leaves
 * its `hs_err` log under the build directory. See `plans/javafx-native-crash-advice.md`.
 */
internal fun childJvmCrashGuardArgs(hsErrDir: String? = System.getProperty(HS_ERR_DIR_PROPERTY)): List<String> {
    val dir: String = hsErrDir ?: return emptyList()
    return listOf("-XX:OnError=kill -9 %p", "-XX:ErrorFile=" + File(dir, "hs_err_pid%p.log").path, "-D$HS_ERR_DIR_PROPERTY=$dir")
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
