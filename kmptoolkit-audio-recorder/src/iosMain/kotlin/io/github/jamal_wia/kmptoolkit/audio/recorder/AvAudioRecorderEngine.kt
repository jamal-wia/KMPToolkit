package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
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
import platform.Foundation.NSURL

/**
 * [RecorderEngine] over `AVAudioRecorder`.
 *
 * `AVAudioRecorder` reports most failures by returning `false` rather than by throwing, and
 * Kotlin/Native cannot catch an Objective-C exception at all. Every `false` is therefore converted
 * into a Kotlin exception here so the state machine sees one uniform failure channel — it surfaces
 * to the consumer as [RecorderError.EngineFailure] with a `null` cause, which is exactly as much as
 * the platform actually told us.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvAudioRecorderEngine : RecorderEngine {

    private var recorder: AVAudioRecorder? = null

    @Suppress("DEPRECATION")
    override fun hasRecordAudioPermission(): Boolean =
        AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

    override fun supportsFormat(format: AudioFormat): Boolean = true

    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig) {
        release()
        activateAudioSession()

        // The initializer is bound as non-null here, but it is a failable Objective-C initializer:
        // it reports a bad path or unusable settings through prepareToRecord() returning false,
        // which is the check below.
        val created = AVAudioRecorder(
            uRL = NSURL.fileURLWithPath(outputPath),
            settings = recordingSettings(config),
            error = null,
        )

        if (!created.prepareToRecord()) {
            created.deleteRecording()
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
        val current: AVAudioRecorder = recorder ?: return
        recorder = null
        if (current.recording) current.stop()
        // The session is deactivated so another app (or another part of this one) regains the
        // audio route as soon as recording ends, rather than whenever this object is collected.
        AVAudioSession.sharedInstance().setActive(false, error = null)
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
