package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
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
import platform.Foundation.NSURL

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
 * deactivates it in [release], including on every failure path in between.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AvAudioRecorderEngine : RecorderEngine {

    private var recorder: AVAudioRecorder? = null
    private var sessionActive: Boolean = false

    @Suppress("DEPRECATION")
    override fun hasRecordAudioPermission(): Boolean =
        AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

    override fun supportsFormat(format: AudioFormat): Boolean = true

    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig) {
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

        if (!created.prepareToRecord()) {
            created.deleteRecording()
            deactivateAudioSession()
            error("AVAudioRecorder.prepareToRecord() failed")
        }
        recorder = created
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

    override fun stop() {
        requireRecorder().stop()
    }

    override fun release() {
        recorder?.let { current ->
            recorder = null
            if (current.recording) current.stop()
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
        session.setCategory(
            category = AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeDefault,
            options = 0u,
            error = null,
        )
        session.setActive(true, error = null)
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
