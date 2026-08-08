package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
 * The instance holds native resources from its first successful [AudioRecorder.prepare] until
 * [AudioRecorder.release] — see the ownership note on [AudioRecorder] for who is expected to call
 * it. `RECORD_AUDIO` is neither declared by this library nor requested by it; see
 * `docs/kmptoolkit-audio-recorder/05-platform-notes.md`.
 *
 * @param context any `Context`. Only its application context is retained, so passing an Activity
 *   cannot leak it.
 * @param config encoder settings, storage layout, and tick interval. Fixed for the recorder's
 *   lifetime.
 */
public fun createAudioRecorder(
    context: Context,
    config: AudioRecorderConfig = AudioRecorderConfig(),
): AudioRecorder {
    val applicationContext: Context = context.applicationContext
    return DefaultAudioRecorder(
        engine = MediaRecorderEngine(applicationContext),
        fileSystem = AndroidRecordingFileSystem(applicationContext),
        config = config,
        // Default, not Main: the only thing this scope runs is the elapsed ticker, which touches
        // no UI and no MediaRecorder — so the module needs no kotlinx-coroutines-android and works
        // in a plain unit test without a main-dispatcher rule.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        epochClock = EpochClock { System.currentTimeMillis() },
    )
}
