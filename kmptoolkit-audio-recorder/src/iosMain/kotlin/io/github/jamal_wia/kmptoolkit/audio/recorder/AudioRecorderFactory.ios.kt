package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
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
 * The `coroutineContext` parameter matches `kmptoolkit-audio-player`'s factories, so a consumer
 * that pins one module's background work pins the other the same way.
 *
 * The instance holds native resources — including the shared `AVAudioSession` — from its first
 * successful [AudioRecorder.prepare] until [AudioRecorder.release]; see the ownership note on
 * [AudioRecorder]. `NSMicrophoneUsageDescription` must be present in the consuming app's
 * `Info.plist`; see `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
 *
 * @param config encoder settings, storage layout, and tick interval. Fixed for the recorder's
 *   lifetime.
 * @param coroutineContext context for the [AudioRecorder.elapsed] ticker and for the filesystem and
 *   encoder work behind [AudioRecorder.prepare], [AudioRecorder.stop], and [AudioRecorder.cancel].
 *   [Dispatchers.Default] rather than [Dispatchers.Main] by default: none of that work touches UI,
 *   and `AVAudioSession` and `AVAudioRecorder` are both safe to drive off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createAudioRecorder(
    config: AudioRecorderConfig = AudioRecorderConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioRecorder = DefaultAudioRecorder(
    engine = AvAudioRecorderEngine(),
    fileSystem = IosRecordingFileSystem(),
    config = config,
    workerContext = coroutineContext,
    epochClock = EpochClock {
        (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
    },
)

private const val MILLIS_PER_SECOND = 1_000
