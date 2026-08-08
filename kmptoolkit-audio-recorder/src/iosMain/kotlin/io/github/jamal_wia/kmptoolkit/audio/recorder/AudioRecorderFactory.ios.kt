package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Creates an [AudioRecorder] backed by `AVAudioRecorder`.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a `Context` and
 * iOS needs nothing — an `expect` signature would have to invent a common context type that this
 * toolkit does not have. Construct the recorder in your platform layer and hold it behind the
 * common [AudioRecorder] interface.
 *
 * ```kotlin
 * val recorder: AudioRecorder = createAudioRecorder()
 * ```
 *
 * The instance holds native resources — including the shared `AVAudioSession` — from its first
 * successful [AudioRecorder.prepare] until [AudioRecorder.release]; see the ownership note on
 * [AudioRecorder]. `NSMicrophoneUsageDescription` must be present in the consuming app's
 * `Info.plist`; see `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
 *
 * @param config encoder settings, storage layout, and tick interval. Fixed for the recorder's
 *   lifetime.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createAudioRecorder(
    config: AudioRecorderConfig = AudioRecorderConfig(),
): AudioRecorder = DefaultAudioRecorder(
    engine = AvAudioRecorderEngine(),
    fileSystem = IosRecordingFileSystem(),
    config = config,
    // Default, not Main: the only thing this scope runs is the elapsed ticker, which touches
    // neither UI nor AVAudioRecorder.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    epochClock = EpochClock {
        (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
    },
)

private const val MILLIS_PER_SECOND = 1_000
