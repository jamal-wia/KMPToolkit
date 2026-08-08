package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything an [AudioRecorder] needs to know that is not a per-recording decision: where files go,
 * how audio is encoded, and how often [AudioRecorder.elapsed] ticks.
 *
 * Fixed at construction and never mutated afterwards — changing any of it means building another
 * recorder, which is also what the platform APIs require, since encoder settings are applied when
 * the native recorder is prepared.
 *
 * The defaults record mono 44.1 kHz AAC-in-MPEG-4 at 128 kbit/s, which is a reasonable voice-note
 * baseline on both platforms.
 *
 * @param storage where recordings are written and how their file names are built.
 * @param format container and encoder. Not every value is supported on every platform — see
 *   [AudioFormat].
 * @param sampleRate samples per second. Must be positive.
 * @param channelCount 1 for mono, 2 for stereo. Must be positive.
 * @param bitRate encoder bit rate in bits per second. Must be positive. Ignored by
 *   [AudioFormat.WAV], which is uncompressed.
 * @param durationUpdateInterval how often [AudioRecorder.elapsed] republishes while recording. Must
 *   be positive. Smaller values give a smoother timer and wake the collector more often; the
 *   default of 100 ms is well below the point where a user perceives a timer as stuttering.
 * @param minimumFreeSpaceBytes free space [AudioRecorder.prepare] insists on before it touches the
 *   microphone, so a doomed recording fails immediately instead of producing a truncated file.
 *   Must not be negative; `0` disables the check. The 8 MiB default is roughly eight minutes at the
 *   default bit rate.
 */
public data class AudioRecorderConfig(
    public val storage: RecordingStorage = RecordingStorage(),
    public val format: AudioFormat = AudioFormat.M4A,
    public val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    public val channelCount: Int = DEFAULT_CHANNEL_COUNT,
    public val bitRate: Int = DEFAULT_BIT_RATE,
    public val durationUpdateInterval: Duration = DEFAULT_DURATION_UPDATE_INTERVAL,
    public val minimumFreeSpaceBytes: Long = DEFAULT_MINIMUM_FREE_SPACE_BYTES,
) {
    init {
        // Validated here rather than reported as a RecorderError: these are values a developer
        // writes as literals, so a wrong one is a bug to fix at the call site, not a runtime
        // condition an app can recover from. The platform encoders would otherwise fail much
        // later, with a message that does not name the field.
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(bitRate > 0) { "bitRate must be positive, was $bitRate" }
        require(durationUpdateInterval > Duration.ZERO) {
            "durationUpdateInterval must be positive, was $durationUpdateInterval"
        }
        require(minimumFreeSpaceBytes >= 0) {
            "minimumFreeSpaceBytes must not be negative, was $minimumFreeSpaceBytes"
        }
    }

    public companion object {
        /** 44.1 kHz — CD sample rate, and the safest value across Android encoders. */
        public const val DEFAULT_SAMPLE_RATE: Int = 44_100

        /** Mono. Voice notes gain nothing from a second channel but double in size. */
        public const val DEFAULT_CHANNEL_COUNT: Int = 1

        /** 128 kbit/s — transparent for speech at the default sample rate. */
        public const val DEFAULT_BIT_RATE: Int = 128_000

        /** 8 MiB. */
        public const val DEFAULT_MINIMUM_FREE_SPACE_BYTES: Long = 8L * 1024 * 1024

        /** 100 ms. */
        public val DEFAULT_DURATION_UPDATE_INTERVAL: Duration = 100.milliseconds

        /**
         * Stereo 48 kHz at 256 kbit/s, for music or anything that will be listened to critically.
         * Roughly four times the bytes per second of the default.
         */
        public val HIGH_QUALITY: AudioRecorderConfig = AudioRecorderConfig(
            sampleRate = 48_000,
            channelCount = 2,
            bitRate = 256_000,
        )
    }
}

/**
 * Where recordings are written and how their file names are built.
 *
 * Every part of this is a parameter rather than a constant inside the library. A library that
 * hardcoded a directory would collide the moment two features of the same app — or two libraries
 * built on this one — recorded at the same time, and would leave a consumer no way to point
 * recordings at a cache directory they are willing to have evicted.
 *
 * @param directoryPath absolute directory to record into. `null` — the default — resolves to
 *   `<app-private files dir>/<directoryName>`, which is `Context.getFilesDir()` on Android and the
 *   app's `Documents` directory on iOS. Supply a path explicitly to record into a cache directory,
 *   an app-group container, or anywhere else you have write access to.
 * @param directoryName subdirectory created under the app-private base directory when
 *   [directoryPath] is `null`. `null` — the default — uses the consumer's own application id
 *   (Android `Context.getPackageName()`) or bundle identifier (iOS `CFBundleIdentifier`), so two
 *   apps that happen to share a directory (an app group, an SD card path) never write into each
 *   other's recordings. Ignored entirely when [directoryPath] is set.
 * @param fileNamePrefix leading part of a generated file name. The generated name is
 *   `"<prefix>_<epochMillis>.<extension>"`, e.g. `recording_1754000000000.m4a`. Only used when
 *   [AudioRecorder.prepare] is called without an explicit path. Must not be blank, and must not
 *   contain a path separator — a prefix is a name, not a nested path.
 */
public data class RecordingStorage(
    public val directoryPath: String? = null,
    public val directoryName: String? = null,
    public val fileNamePrefix: String = DEFAULT_FILE_NAME_PREFIX,
) {
    init {
        require(fileNamePrefix.isNotBlank()) { "fileNamePrefix must not be blank" }
        require(!fileNamePrefix.contains('/') && !fileNamePrefix.contains('\\')) {
            "fileNamePrefix must not contain a path separator, was '$fileNamePrefix'"
        }
        require(directoryPath == null || directoryPath.isNotBlank()) {
            "directoryPath must be null or a non-blank absolute path"
        }
        require(directoryName == null || directoryName.isNotBlank()) {
            "directoryName must be null or a non-blank directory name"
        }
    }

    public companion object {
        /** `"recording"`. */
        public const val DEFAULT_FILE_NAME_PREFIX: String = "recording"
    }
}

/**
 * Container and encoder a recording is written with.
 *
 * The set is deliberately small: every value here is one a platform can genuinely produce. MP3 is
 * absent because neither Android's `MediaRecorder` nor `AVAudioRecorder` has an MP3 encoder, and
 * silently writing AAC into a `.mp3` file — which the code this module was ported from did — hands
 * the consumer a mislabelled file.
 *
 * @param extension file extension the generated name uses, without a leading dot.
 */
public enum class AudioFormat(public val extension: String) {

    /** AAC in an MPEG-4 container. Supported on both platforms; the default. */
    M4A("m4a"),

    /** Raw AAC in an ADTS stream. Supported on both platforms. */
    AAC("aac"),

    /**
     * Uncompressed 16-bit linear PCM in a WAVE container.
     *
     * **iOS only.** Android's `MediaRecorder` has no PCM/WAV output format, so
     * [AudioRecorder.prepare] fails there with [RecorderError.UnsupportedFormat] rather than
     * quietly writing some other codec into a `.wav` file.
     */
    WAV("wav"),
}
