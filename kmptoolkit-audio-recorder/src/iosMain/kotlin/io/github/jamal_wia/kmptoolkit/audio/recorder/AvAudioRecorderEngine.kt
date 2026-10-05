package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.concurrent.Volatile
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioRecorderDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

/**
 * [RecorderEngine] over `AVAudioRecorder`.
 *
 * `AVAudioRecorder` reports most failures by returning `false` rather than by throwing, and
 * Kotlin/Native cannot catch an Objective-C exception at all. Every `false` is therefore converted
 * into a Kotlin exception here so the state machine sees one uniform failure channel — it surfaces
 * to the consumer as [RecorderError.EngineFailure] with a `null` cause, which is exactly as much as
 * the platform actually told us. The one call that does hand back an `NSError` — the initializer —
 * has it captured and folded into the exception's message.
 *
 * This engine also owns the process-wide `AVAudioSession`: it activates it in [prepare] and
 * deactivates it in [release], including on every failure path in between. Activation errors are
 * captured and thrown, so a session that cannot be configured fails `prepare` with its own cause
 * instead of failing later as a misleading `start` failure.
 *
 * It also reports what the platform does on its own, as raw [EngineEvent]s, from three sources: the
 * recorder's delegate (an encode error, or a recording that finished unsuccessfully without being
 * asked to), `AVAudioSessionInterruptionNotification` (a call, Siri, an alarm, another app, or the
 * app having been suspended — an interruption that has ended is ignored, nothing resumes by
 * itself), and `AVAudioSessionMediaServicesWereResetNotification`. `AVAudioRecorder.delegate` is
 * weak, so the delegate is held here by a strong field; the notification observers are removed in
 * [release] and before a normal [stop], so a recording the app ended is never reported as one the
 * system ended.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AvAudioRecorderEngine : RecorderEngine {

    // Volatile because peakDbfs() reads it from the metering coroutine while release() nulls it on
    // the caller's thread — the one cross-thread read in this class.
    @Volatile
    private var recorder: AVAudioRecorder? = null
    private var channelCount: Int = 1
    private var sessionActive: Boolean = false

    // Read by delegate and notification callbacks on whichever thread they arrive on, written by
    // setEventListener, stop and release.
    @Volatile
    private var listener: ((EngineEvent) -> Unit)? = null

    // A strong reference: AVAudioRecorder.delegate is a weak property, so a delegate held only by
    // the recorder would be deallocated and the callbacks would silently never arrive.
    private val delegate: RecorderDelegate = RecorderDelegate { event: EngineEvent ->
        listener?.invoke(event)
    }

    // Tokens of the NSNotificationCenter observers, kept so they can be removed.
    private var observers: List<NSObjectProtocol> = emptyList()

    @Suppress("DEPRECATION")
    override fun hasRecordAudioPermission(): Boolean =
        AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

    override fun supportsFormat(format: AudioFormat): Boolean = true

    override suspend fun prepare(
        outputPath: String,
        config: AudioRecorderConfig,
        maxFileSizeBytes: Long?,
    ) {
        release()
        activateAudioSession()

        // -[AVAudioRecorder initWithURL:settings:error:] is a *failable* initializer: it returns nil
        // and fills in `error` when the settings dictionary is unusable — a sample rate or channel
        // count CoreAudio rejects, say. Kotlin/Native binds it as non-null, so that nil would be an
        // unchecked crash rather than a typed error. Capturing the NSError and throwing on it turns
        // the whole class of bad-settings failures into RecorderError.EngineFailure, and is the only
        // path on this platform where a cause is available at all.
        val created: AVAudioRecorder = memScoped {
            val errorRef = alloc<ObjCObjectVar<NSError?>>()
            val recorder = AVAudioRecorder(
                uRL = NSURL.fileURLWithPath(outputPath),
                settings = recordingSettings(config),
                error = errorRef.ptr,
            )
            errorRef.value?.let { failure ->
                deactivateAudioSession()
                error("AVAudioRecorder could not be created: ${failure.localizedDescription}")
            }
            recorder
        }

        // Always on, never toggled on a live recorder: enabling metering is a flag on a pipeline
        // that is running anyway and costs nothing measurable, whereas flipping it while recording
        // is exactly the kind of mid-flight state change this class avoids. Whether anything reads
        // the meters is decided by DefaultAudioRecorder, which only calls peakDbfs() on demand.
        created.meteringEnabled = true
        channelCount = config.channelCount
        // Only once the recorder exists; setEventListener re-attaches it for a recorder made later.
        if (listener != null) created.delegate = delegate

        if (!created.prepareToRecord()) {
            created.deleteRecording()
            deactivateAudioSession()
            error("AVAudioRecorder.prepareToRecord() failed")
        }
        recorder = created
    }

    override fun setEventListener(listener: ((EngineEvent) -> Unit)?) {
        this.listener = listener
        if (listener == null) removeObservers() else addObservers()
        recorder?.delegate = if (listener == null) null else delegate
    }

    /** The recorder's delegate, for the module's own tests to drive its callbacks directly. */
    internal val recorderDelegate: RecorderDelegate get() = delegate

    /** The platform recorder, for the module's own tests. */
    internal val activeRecorder: AVAudioRecorder? get() = recorder

    private fun addObservers() {
        if (observers.isNotEmpty()) return
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        // queue = null: the block runs on the posting thread, and the listener never blocks.
        observers = listOf(
            center.addObserverForName(
                name = AVAudioSessionInterruptionNotification,
                `object` = session,
                queue = null,
            ) { notification: NSNotification? ->
                val type: NSNumber? = notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
                mapAudioSessionInterruption(type?.unsignedLongValue)?.let { reason: InterruptionReason ->
                    listener?.invoke(EngineEvent.Interrupted(reason))
                }
            },
            center.addObserverForName(
                name = AVAudioSessionMediaServicesWereResetNotification,
                `object` = session,
                queue = null,
            ) { _: NSNotification? ->
                listener?.invoke(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
            },
        )
    }

    private fun removeObservers() {
        val center: NSNotificationCenter = NSNotificationCenter.defaultCenter
        observers.forEach { token: NSObjectProtocol -> center.removeObserver(token) }
        observers = emptyList()
    }

    override fun start() {
        check(requireRecorder().record()) { "AVAudioRecorder.record() failed" }
    }

    override fun pause() {
        requireRecorder().pause()
    }

    override fun resume() {
        check(requireRecorder().record()) { "AVAudioRecorder.record() failed on resume" }
    }

    override fun peakDbfs(): Float? {
        val current: AVAudioRecorder = recorder ?: return null
        if (!current.recording) return null
        current.updateMeters()
        // peakPower, not averagePower: MediaRecorder reports a peak, and the two platforms must
        // draw the same bar for the same voice. The loudest channel wins, as on Android.
        var peak: Float = Float.NEGATIVE_INFINITY
        for (channel in 0 until channelCount) {
            peak = maxOf(peak, current.peakPowerForChannel(channel.toULong()))
        }
        return peak
    }

    override fun stop() {
        val current: AVAudioRecorder = requireRecorder()
        // Before stopping, so the delegate callback stop() itself causes (didFinishRecording) and
        // anything else the platform does in reaction is never taken for an event — and the
        // listener itself goes too, so a callback already in flight finds nobody to tell.
        listener = null
        removeObservers()
        current.delegate = null
        current.stop()
    }

    override fun release() {
        listener = null
        removeObservers()
        recorder?.let { current ->
            recorder = null
            current.delegate = null
            // Unconditionally: an interrupted recorder reports `recording == false` while still
            // holding its file open, and skipping stop() would leave that file unfinalized.
            current.stop()
        }
        // Deactivated independently of the recorder: prepare() activates the session before it
        // constructs the recorder, so a construction failure leaves an active session with no
        // recorder to hang it off. Gating this on a non-null recorder would strand the whole
        // process with PlayAndRecord active — every other app's audio ducked, indefinitely.
        deactivateAudioSession()
    }

    private fun requireRecorder(): AVAudioRecorder =
        recorder ?: error("AVAudioRecorder is not prepared")

    private fun activateAudioSession() {
        val session: AVAudioSession = AVAudioSession.sharedInstance()
        // The NSError of both calls is captured and thrown: a category or activation the system
        // refuses (another app holding the session, a restricted device) is a PREPARE failure with
        // its own cause, not a mystery `record()` failure later on.
        memScoped {
            val errorRef = alloc<ObjCObjectVar<NSError?>>()
            val categorySet: Boolean = session.setCategory(
                category = AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = errorRef.ptr,
            )
            if (!categorySet || errorRef.value != null) {
                error("AVAudioSession could not be configured: ${errorRef.value?.localizedDescription}")
            }
            val activated: Boolean = session.setActive(true, error = errorRef.ptr)
            if (!activated || errorRef.value != null) {
                error("AVAudioSession could not be activated: ${errorRef.value?.localizedDescription}")
            }
        }
        sessionActive = true
    }

    private fun deactivateAudioSession() {
        if (!sessionActive) return
        sessionActive = false
        // NotifyOthersOnDeactivation: without it a backgrounded music app stays paused until it
        // notices on its own, instead of resuming as soon as the microphone is free.
        AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }

    private fun recordingSettings(config: AudioRecorderConfig): Map<Any?, Any> =
        when (config.format) {
            AudioFormat.M4A, AudioFormat.AAC -> mapOf(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVSampleRateKey to config.sampleRate.toDouble(),
                AVNumberOfChannelsKey to config.channelCount,
                AVEncoderBitRateKey to config.bitRate,
                AVEncoderAudioQualityKey to AVAudioQualityHigh,
            )

            AudioFormat.WAV -> mapOf(
                AVFormatIDKey to kAudioFormatLinearPCM,
                AVSampleRateKey to config.sampleRate.toDouble(),
                AVNumberOfChannelsKey to config.channelCount,
                AVLinearPCMBitDepthKey to WAV_BIT_DEPTH,
                AVLinearPCMIsFloatKey to false,
                AVLinearPCMIsBigEndianKey to false,
            )
        }

    private companion object {
        const val WAV_BIT_DEPTH = 16
    }
}

/**
 * Maps the `AVAudioSessionInterruptionTypeKey` of an `AVAudioSessionInterruptionNotification` to the
 * reason it ends a recording for. An interruption that began is [InterruptionReason.AudioSessionInterrupted]
 * — including the case where the app was suspended without the `audio` background mode, which iOS
 * reports with the same type (reason `AppWasSuspended`) only once the app returns. One that has
 * ended, or a notification without a type, ends nothing: the recording is already over, and nothing
 * resumes by itself.
 */
internal fun mapAudioSessionInterruption(type: ULong?): InterruptionReason? =
    if (type == AVAudioSessionInterruptionTypeBegan) InterruptionReason.AudioSessionInterrupted else null

/**
 * The `AVAudioRecorderDelegate` of [AvAudioRecorderEngine], reduced to forwarding. An encode error
 * is [InterruptionReason.EngineDied] carrying the `NSError` code. A recording that finished
 * unsuccessfully is the same without a code; one that finished successfully ends nothing — the
 * engine stops listening before it calls `stop()`, so a delegate callback can only be one the
 * platform started.
 */
@OptIn(BetaInteropApi::class)
internal class RecorderDelegate(
    private val report: (EngineEvent) -> Unit,
) : NSObject(), AVAudioRecorderDelegateProtocol {

    override fun audioRecorderEncodeErrorDidOccur(recorder: AVAudioRecorder, error: NSError?) {
        report(EngineEvent.Interrupted(InterruptionReason.EngineDied(error?.code?.toInt())))
    }

    override fun audioRecorderDidFinishRecording(recorder: AVAudioRecorder, successfully: Boolean) {
        if (!successfully) report(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
    }
}
