package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [RecorderEngine] over `android.media.MediaRecorder`.
 *
 * A fresh `MediaRecorder` is built per [prepare] and destroyed by [release]. Reusing one across
 * recordings would mean driving `reset()` correctly from every state the platform machine can be
 * in; constructing a new one costs a negligible allocation and removes that entire class of bug.
 */
internal class MediaRecorderEngine(
    private val context: Context,
) : RecorderEngine {

    // Volatile because prepare() assigns it from Dispatchers.IO while release() may read it from
    // the caller's thread — the one interleaving the single-threaded contract still permits, since
    // release() is allowed to run while prepare() is suspended.
    @Volatile
    private var recorder: MediaRecorder? = null

    override fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun supportsFormat(format: AudioFormat): Boolean = when (format) {
        AudioFormat.M4A, AudioFormat.AAC -> true
        // MediaRecorder has no linear-PCM output format. Writing AAC into a `.wav` file instead
        // would hand the consumer a file whose extension lies about its contents.
        AudioFormat.WAV -> false
    }

    override suspend fun prepare(outputPath: String, config: AudioRecorderConfig) {
        release()
        // MediaRecorder.prepare() opens the file and the audio hardware, both blocking.
        withContext(Dispatchers.IO) {
            val created: MediaRecorder = newRecorder()
            try {
                created.configure(outputPath, config)
                created.prepare()
            } catch (failure: Throwable) {
                created.release()
                throw failure
            }
            recorder = created
        }
    }

    override fun start() {
        requireRecorder().start()
    }

    override fun pause() {
        // Available unconditionally: pause/resume landed in API 24, which is this library's minSdk.
        // It still throws when the active output format does not support it.
        requireRecorder().pause()
    }

    override fun resume() {
        requireRecorder().resume()
    }

    override fun stop() {
        requireRecorder().stop()
    }

    override fun release() {
        val current: MediaRecorder = recorder ?: return
        recorder = null
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            current.release()
        } catch (failure: Throwable) {
            // release() is the failure path of everything else and must not throw. The handle is
            // already unreachable, so there is nothing left to do about it either way.
        }
    }

    private fun requireRecorder(): MediaRecorder =
        recorder ?: error("MediaRecorder is not prepared")

    private fun MediaRecorder.configure(outputPath: String, config: AudioRecorderConfig) {
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
