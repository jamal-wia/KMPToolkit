package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * Creates an [AudioRecorder] backed by `android.media.MediaRecorder`.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a [Context] and
 * iOS needs nothing — an `expect` signature would have to invent a common context type that this
 * toolkit does not have. Construct the recorder in your platform layer and hold it behind the
 * common [AudioRecorder] interface.
 *
 * ```kotlin
 * val recorder: AudioRecorder = createAudioRecorder(context)
 * ```
 *
 * The `coroutineContext` parameter matches `kmptoolkit-audio-player`'s factories, so a consumer
 * that pins one module's background work pins the other the same way.
 *
 * The instance holds native resources from its first successful [AudioRecorder.prepare] until
 * [AudioRecorder.release] — see the ownership note on [AudioRecorder] for who is expected to call
 * it. `RECORD_AUDIO` is neither declared by this library nor requested by it; see
 * `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
 *
 * @param context any `Context`. Only its application context is retained, so passing an Activity
 *   cannot leak it.
 * @param config encoder settings, storage layout, and tick interval. Fixed for the recorder's
 *   lifetime.
 * @param coroutineContext context for the [AudioRecorder.elapsed] ticker and for the filesystem and
 *   encoder work behind [AudioRecorder.prepare], [AudioRecorder.stop], and [AudioRecorder.cancel].
 *   [Dispatchers.Default] rather than [Dispatchers.Main] by default: none of that work touches UI,
 *   so the module needs no `kotlinx-coroutines-android` and runs in a plain unit test without a
 *   main-dispatcher rule.
 */
public fun createAudioRecorder(
    context: Context,
    config: AudioRecorderConfig = AudioRecorderConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioRecorder {
    val applicationContext: Context = context.applicationContext
    return DefaultAudioRecorder(
        engine = MediaRecorderEngine(applicationContext),
        fileSystem = AndroidRecordingFileSystem(applicationContext),
        config = config,
        workerContext = coroutineContext,
        epochClock = EpochClock { System.currentTimeMillis() },
    )
}
