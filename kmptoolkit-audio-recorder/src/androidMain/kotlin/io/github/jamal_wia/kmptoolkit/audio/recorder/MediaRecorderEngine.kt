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
    private val silenceMonitors: InputSilenceMonitor.Factory = InputSilenceMonitor.Factory(::RecordingSilenceMonitor),
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
    private var silenceMonitor: InputSilenceMonitor? = null

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
        val current: MediaRecorder? = recorder
        // Whatever an earlier listener installed goes first: attaching again on top of it would
        // register a second silence monitor, and every change would then be reported twice.
        if (current != null) detachCallbacks(current)
        this.listener = listener
        if (current != null && listener != null) attachCallbacks(current, listener)
    }

    /**
     * Installs the platform callbacks for [attached]. Each one delivers only while [attached] is
     * still the engine's listener, so a callback that was in flight when the listener was replaced
     * or removed — the platform can deliver one after `setOnErrorListener(null)` returned — finds
     * nobody to tell, instead of reaching whatever listener is set by then.
     */
    private fun attachCallbacks(current: MediaRecorder, attached: (EngineEvent) -> Unit) {
        val deliver: (EngineEvent) -> Unit = { event: EngineEvent ->
            if (listener === attached) attached(event)
        }
        current.setOnErrorListener { _: MediaRecorder, what: Int, extra: Int ->
            deliver(EngineEvent.Interrupted(mapMediaRecorderError(what, extra)))
        }
        current.setOnInfoListener { _: MediaRecorder, what: Int, _: Int ->
            mapMediaRecorderInfo(what)?.let { reason: InterruptionReason ->
                deliver(EngineEvent.Interrupted(reason))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val monitor: InputSilenceMonitor = silenceMonitors.create(current) { silenced: Boolean ->
                deliver(EngineEvent.InputSilenced(silenced))
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
        listener = null
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
 * when it ends nothing. `MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED` is [InterruptionReason.StorageLow].
 * Usually that is the limit this library sets from the free space at prepare, but it can also fire
 * with none reached: the MPEG-4 writer has an implicit limit of its own, derived from the volume
 * (`fpathconf(_PC_FILESIZEBITS)`, 4 GiB on a FAT32 volume reached through
 * [RecordingStorage.directoryPath]). The mapping is kept — the recording did end because of the
 * size of the file — and documented on [InterruptionReason.StorageLow]. A duration limit is never
 * set, so `MAX_DURATION_REACHED` cannot fire and is ignored with every other informational code
 * (including "file size approaching").
 */
internal fun mapMediaRecorderInfo(what: Int): InterruptionReason? = when (what) {
    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> InterruptionReason.StorageLow
    else -> null
}

/**
 * The engine's view of the Android 10+ silencing monitor, so a test can stand in for the platform
 * class that has no Robolectric shadow. Reports through the callback given to [Factory.create]:
 * `true` when the input was silenced, `false` when it was released again. Takes no decisions —
 * whether a silence ends the recording, and after how long, is decided in common code.
 */
internal interface InputSilenceMonitor {

    /** Starts listening for changes. Best effort: a platform that refuses leaves no detection. */
    fun register()

    /** Stops listening. Safe to call when never registered or already released. */
    fun unregister()

    /** Reports `true` when the input is silenced right now; a silenced start has no change to wait for. */
    fun checkNow()

    fun interface Factory {
        fun create(recorder: MediaRecorder, onSilenced: (Boolean) -> Unit): InputSilenceMonitor
    }
}

/**
 * Android 10+ glue (only ever constructed behind an `SDK_INT >= Q` check) between `MediaRecorder`'s
 * `AudioRecordingMonitor` and [EngineEvent.InputSilenced]. Kept to registration and one read.
 *
 * It is registered with a direct executor, so the callback runs on the monitor's own
 * `HandlerThread` inside the platform (`AudioRecordingMonitorImpl`) — not on a binder thread and
 * not on an application Looper. That is fine, since the listener it reports to never blocks.
 * Registration is best effort: a platform that refuses it leaves a recorder that works and merely
 * cannot tell it was silenced, the same as on API 24 to 28.
 */
@TargetApi(Build.VERSION_CODES.Q)
internal class RecordingSilenceMonitor(
    private val recorder: MediaRecorder,
    private val onSilenced: (Boolean) -> Unit,
) : InputSilenceMonitor {
    private val callback: AudioManager.AudioRecordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                // The platform hands a monitor the configuration of its own recorder only, so the
                // first entry is the one that matters; an empty list reports "not silenced".
                onSilenced(configs.firstOrNull()?.isClientSilenced == true)
            }
        }

    override fun register() {
        try {
            recorder.registerAudioRecordingCallback(DirectExecutor, callback)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            // See the class note: no silence detection, nothing else lost.
        }
    }

    override fun unregister() {
        try {
            recorder.unregisterAudioRecordingCallback(callback)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") failure: RuntimeException) {
            // Already released or never registered; either way nothing will be delivered.
        }
    }

    override fun checkNow() {
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
