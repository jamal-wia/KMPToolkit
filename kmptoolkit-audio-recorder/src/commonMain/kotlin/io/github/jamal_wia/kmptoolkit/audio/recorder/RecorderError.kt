package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * Why an [AudioRecorder] operation failed.
 *
 * These are typed causes, not messages: nothing here is meant to be shown to a user. Mapping a
 * cause onto copy in the right language is the consuming app's job — see
 * `docs/01-architecture.md`.
 */
public sealed interface RecorderError {

    /**
     * `RECORD_AUDIO` is not granted. The library never requests it; ask for it in your own
     * permission flow and call [AudioRecorder.prepare] again once the user has granted it.
     */
    public data object PermissionDenied : RecorderError

    /**
     * [operation] is not legal in [state]. See the transition table on [AudioRecorder]. The
     * recorder's state is unchanged — this failure is inert.
     */
    public data class IllegalState(
        public val state: RecorderState,
        public val operation: RecorderOperation,
    ) : RecorderError

    /**
     * [AudioRecorder.release] has already been called, so [operation] can never succeed on this
     * instance. Construct a new recorder.
     */
    public data class AlreadyReleased(public val operation: RecorderOperation) : RecorderError

    /**
     * The output directory could not be created, or exists but cannot be written to — a path
     * outside the app sandbox, a revoked scoped-storage grant, or a read-only volume.
     */
    public data class DirectoryNotWritable(
        public val path: String,
        public val cause: Throwable? = null,
    ) : RecorderError

    /**
     * The volume holding the output path has less than [requiredBytes] free, so recording would
     * fail partway through with a truncated file. Checked before the microphone is touched.
     *
     * @param availableBytes free space the platform reported, or `-1` if it could not be
     *   determined.
     */
    public data class InsufficientStorage(
        public val path: String,
        public val requiredBytes: Long,
        public val availableBytes: Long,
    ) : RecorderError

    /**
     * The requested [format] has no native encoder on this platform. Currently this is only
     * [AudioFormat.WAV] on Android — see `docs/kmptoolkit-audio-recorder/05-platform-notes.md` for
     * the support matrix.
     */
    public data class UnsupportedFormat(public val format: AudioFormat) : RecorderError

    /**
     * The platform recorder rejected [operation]. [cause] is the underlying exception where the
     * platform gave one — `null` when the platform API reports failure by returning `false` rather
     * than throwing, which `AVAudioRecorder` does.
     *
     * This is always the result of a call *you* made. A failure that happens on its own
     * mid-recording — the microphone taken by a phone call, the media server dying — is **not**
     * pushed here; it surfaces at the next operation, usually [RecorderOperation.STOP]. See
     * `docs/kmptoolkit-audio-recorder/05-platform-notes.md` for why, and for what to observe
     * yourself if you need to react while it happens.
     */
    public data class EngineFailure(
        public val operation: RecorderOperation,
        public val cause: Throwable? = null,
    ) : RecorderError
}

/** The operation an error refers to. Mirrors the methods of [AudioRecorder]. */
public enum class RecorderOperation {

    /** [AudioRecorder.prepare]. */
    PREPARE,

    /** [AudioRecorder.start]. */
    START,

    /** [AudioRecorder.pause]. */
    PAUSE,

    /** [AudioRecorder.resume]. */
    RESUME,

    /** [AudioRecorder.stop]. */
    STOP,

    /** [AudioRecorder.cancel]. */
    CANCEL,

    // No RELEASE entry: AudioRecorder.release() cannot fail, so no error ever refers to it.
}
