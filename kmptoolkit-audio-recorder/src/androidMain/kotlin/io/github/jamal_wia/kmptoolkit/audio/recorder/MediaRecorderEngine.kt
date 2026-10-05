package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import java.util.concurrent.Executor
import kotlin.math.log10

/**
 * [RecorderEngine] over `android.media.MediaRecorder`.
 *
 * A fresh `MediaRecorder` is built per [prepare] and destroyed by [release]. Reusing one across
 * recordings would mean driving `reset()` correctly from every state the platform machine can be
 * in; constructing a new one costs a negligible allocation and removes that entire class of bug.
 *
 * It also reports what the platform does on its own, as raw [EngineEvent]s: `MediaRecorder`'s error
 * and info callbacks, and — on Android 10 and newer — whether the OS silenced the input. The
 * callbacks arrive on the Looper of the thread that created the recorder, or the main Looper; the
 * engine never assumes which and only forwards, because the listener it reports to never blocks.
 * Every callback is removed before [stop] and in [release], so a recording the app ended is never
 * reported as one the system ended.
 */
internal class MediaRecorderEngine(
    private val context: Context,
) : RecorderEngine {

    // Volatile because prepare() assigns it on the worker context while release() may read it from
    // the caller's thread — the one interleaving the single-threaded contract still permits, since
    // release() is allowed to run while prepare() is suspended.
    @Volatile
    private var recorder: MediaRecorder? = null

    // Read by platform callbacks on whichever thread they arrive on, written by setEventListener.
    @Volatile
    private var listener: ((EngineEvent) -> Unit)? = null

    @Volatile
    private var silenceMonitor: RecordingSilenceMonitor? = null

    /** The platform recorder, for the module's own tests to reach its Robolectric shadow. */
    internal val activeRecorder: MediaRecorder? get() = recorder

    override fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun supportsFormat(format: AudioFormat): Boolean = when (format) {
        AudioFormat.M4A, AudioFormat.AAC -> true
        // MediaRecorder has no linear-PCM output format. Writing AAC into a `.wav` file instead
        // would hand the consumer a file whose extension lies about its contents.
        AudioFormat.WAV -> false
    }

    override suspend fun prepare(
        outputPath: String,
        config: AudioRecorderConfig,
        maxFileSizeBytes: Long?,
    ) {
        release()
        // No withContext here: DefaultAudioRecorder already calls this on the worker context the
        // consumer chose, and an engine that picked its own dispatcher would quietly override it.
        val created: MediaRecorder = newRecorder()
        try {
            created.configure(outputPath, config, maxFileSizeBytes)
            created.prepare()
        } catch (failure: Throwable) {
            created.release()
            throw failure
        }
        recorder = created
    }

    override fun setEventListener(listener: ((EngineEvent) -> Unit)?) {
        this.listener = listener
        val current: MediaRecorder = recorder ?: return
        if (listener == null) detachCallbacks(current) else attachCallbacks(current)
    }

    private fun attachCallbacks(current: MediaRecorder) {
        current.setOnErrorListener { _: MediaRecorder, what: Int, extra: Int ->
            listener?.invoke(EngineEvent.Interrupted(mapMediaRecorderError(what, extra)))
        }
        current.setOnInfoListener { _: MediaRecorder, what: Int, _: Int ->
            mapMediaRecorderInfo(what)?.let { reason: InterruptionReason ->
                listener?.invoke(EngineEvent.Interrupted(reason))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val monitor = RecordingSilenceMonitor(current) { silenced: Boolean ->
                listener?.invoke(EngineEvent.InputSilenced(silenced))
            }
            silenceMonitor = monitor
            monitor.register()
        }
    }

    private fun detachCallbacks(current: MediaRecorder) {
        current.setOnErrorListener(null)
        current.setOnInfoListener(null)
        silenceMonitor?.unregister()
        silenceMonitor = null
    }

    override fun start() {
        requireRecorder().start()
        // A recording that begins already silenced produces no configuration change to hear about.
        silenceMonitor?.checkNow()
    }

    override fun pause() {
        // Available unconditionally: pause/resume landed in API 24, which is this library's minSdk.
        // It still throws when the active output format does not support it.
        requireRecorder().pause()
    }

    override fun resume() {
        requireRecorder().resume()
        silenceMonitor?.checkNow()
    }

    override fun peakDbfs(): Float? {
        // Snapshotted once: release() on the caller's thread nulls the field, and the reference used
        // below must not change between the check and the call.
        val current: MediaRecorder = recorder ?: return null
        return try {
            amplitudeToDbfs(current.maxAmplitude)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            // getMaxAmplitude() throws IllegalStateException outside the recording state, which
            // includes the moment between a pause()/stop()/release() and the metering coroutine
            // noticing it. That is an ordinary answer ("nothing to measure"), not a failure.
            null
        }
    }

    override fun stop() {
        val current: MediaRecorder = requireRecorder()
        // Before stopping, so the platform's own reaction to stop() is never taken for an event —
        // and the listener itself goes too, so a callback already in flight finds nobody to tell.
        listener = null
        detachCallbacks(current)
        current.stop()
    }

    override fun release() {
        val current: MediaRecorder = recorder ?: return
        recorder = null
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        listener = null
        try {
            detachCallbacks(current)
            current.release()
        } catch (failure: Throwable) {
            // release() is the failure path of everything else and must not throw. The handle is
            // already unreachable, so there is nothing left to do about it either way.
        }
    }

    private fun requireRecorder(): MediaRecorder =
        recorder ?: error("MediaRecorder is not prepared")

    private fun MediaRecorder.configure(
        outputPath: String,
        config: AudioRecorderConfig,
        maxFileSizeBytes: Long?,
    ) {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        when (config.format) {
            AudioFormat.M4A -> setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            AudioFormat.AAC -> setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            AudioFormat.WAV -> error("WAV is rejected before the engine is reached")
        }
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(config.sampleRate)
        setAudioChannels(config.channelCount)
        setAudioEncodingBitRate(config.bitRate)
        // The platform's own stop at a size limit, as a backstop for a disk that fills faster than
        // the free-space watchdog polls. Reported as MAX_FILESIZE_REACHED, which is StorageLow.
        maxFileSizeBytes?.let { limit: Long -> setMaxFileSize(limit) }
        setOutputFile(outputPath)
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}

/**
 * Converts a `MediaRecorder.getMaxAmplitude()` reading (`0..32767`, the peak 16-bit sample since
 * the previous call, already the maximum across channels) to dBFS: `0` is digital silence and
 * `32767` is full scale. Capped at `0f` because an out-of-range reading must not report a level
 * above full scale.
 */
internal fun amplitudeToDbfs(amplitude: Int): Float {
    if (amplitude <= 0) return Float.NEGATIVE_INFINITY
    return minOf(0f, 20f * log10(amplitude / FULL_SCALE_AMPLITUDE))
}

private const val FULL_SCALE_AMPLITUDE: Float = 32_767f

/**
 * Maps a `MediaRecorder.OnErrorListener` report to the reason it ends a recording for. Every
 * error the listener delivers means the recorder is broken beyond use — the documented ones are
 * `MEDIA_RECORDER_ERROR_UNKNOWN` (1) and `MEDIA_ERROR_SERVER_DIED` (100) — so every `what` maps to
 * [InterruptionReason.EngineDied].
 *
 * The platform code is `what`, except for `MEDIA_RECORDER_ERROR_UNKNOWN` with a non-zero `extra`,
 * where `extra` is the more specific number (an implementation-defined error code).
 */
internal fun mapMediaRecorderError(what: Int, extra: Int): InterruptionReason.EngineDied {
    val code: Int =
        if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN && extra != 0) extra else what
    return InterruptionReason.EngineDied(platformCode = code)
}

/**
 * Maps a `MediaRecorder.OnInfoListener` report to the reason it ends a recording for, or `null`
 * when it ends nothing. `MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED` is [InterruptionReason.StorageLow]
 * — the only size limit is the one this library sets from the free space at prepare. A duration
 * limit is never set, so `MAX_DURATION_REACHED` cannot fire and is ignored with every other
 * informational code (including "file size approaching").
 */
internal fun mapMediaRecorderInfo(what: Int): InterruptionReason? = when (what) {
    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> InterruptionReason.StorageLow
    else -> null
}

/**
 * Android 10+ glue (only ever constructed behind an `SDK_INT >= Q` check) between `MediaRecorder`'s `AudioRecordingMonitor` and [EngineEvent.InputSilenced].
 * Kept to registration and one read, with no decisions: whether a silence ends the recording, and
 * after how long, is decided in common code.
 *
 * The callback runs on a direct executor, so it needs no Looper and arrives on a binder thread —
 * which is fine, since the listener never blocks. Registration is best effort: a platform that
 * refuses it leaves a recorder that works and merely cannot tell it was silenced, the same as on
 * API 24 to 28.
 */
@TargetApi(Build.VERSION_CODES.Q)
internal class RecordingSilenceMonitor(
    private val recorder: MediaRecorder,
    private val onSilenced: (Boolean) -> Unit,
) {
    private val callback: AudioManager.AudioRecordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                // A monitor reports its own recorder only; no configuration means not capturing.
                onSilenced(configs.firstOrNull()?.isClientSilenced == true)
            }
        }

    fun register() {
        try {
            recorder.registerAudioRecordingCallback(DirectExecutor, callback)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            // See the class note: no silence detection, nothing else lost.
        }
    }

    fun unregister() {
        try {
            recorder.unregisterAudioRecordingCallback(callback)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            // Already released or never registered; either way nothing will be delivered.
        }
    }

    /** Reports `true` when the input is silenced right now; a silenced start has no change to wait for. */
    fun checkNow() {
        val silenced: Boolean = try {
            recorder.activeRecordingConfiguration?.isClientSilenced == true
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            false
        }
        if (silenced) onSilenced(true)
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
