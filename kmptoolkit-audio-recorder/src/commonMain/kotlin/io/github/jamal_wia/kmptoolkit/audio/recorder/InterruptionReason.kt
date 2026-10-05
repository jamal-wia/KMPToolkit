package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * Why the system, not the app, ended a recording. Carried by [RecorderState.Interrupted] when the
 * file could be finalized and by [RecorderError.RecordingLost] when it could not.
 *
 * Typed causes, not messages — mapping one onto copy in the right language is the consuming app's
 * job. The set is closed: a `when` over it is exhaustive.
 */
public sealed interface InterruptionReason {

    /**
     * The audio session was taken away by the system: a phone call, Siri, an alarm, another app
     * that was given priority. iOS only — `AVAudioSession` interruption began. It is also the
     * reason when the app was suspended while recording without the `audio` background mode
     * (`appWasSuspended`), which iOS reports only once the app returns. In that case the duration
     * is measured up to the moment the interruption was observed, on return, so it can include
     * the time the app was suspended; stop the recording when the app goes to the background if
     * that matters.
     *
     * The recorder never resumes by itself when the interruption ends: the recording is over, and
     * the file holds what was captured up to the moment it began.
     */
    public data object AudioSessionInterrupted : InterruptionReason

    /**
     * The OS kept the recorder running but silenced its input, so the file keeps growing with
     * silence. Android 10 (API 29) and newer: a phone call, another app with capture priority, or
     * this app capturing from the background without a microphone foreground service. Reported
     * only after the input has stayed silenced for a short debounce, so a brief handover does not
     * end a recording. Not detectable on Android API 24 to 28, where such a recording simply
     * records silence.
     *
     * [RecorderState.Interrupted.recording]'s duration stops at the moment the silencing began,
     * not when it was reported.
     */
    public data object MicrophoneSilenced : InterruptionReason

    /**
     * The volume holding the file ran below the free-space reserve the library keeps so it can
     * still finalize the file — on Android also when `MediaRecorder` reported
     * `MAX_FILESIZE_REACHED`. That is usually the size limit the library set at prepare, but the
     * platform has one of its own too: MPEG-4 recording stops at the largest file the volume can
     * hold (about 4 GiB on a FAT32 volume, which [RecordingStorage.directoryPath] can point at),
     * and that is reported the same way. Enabled by [AudioRecorderConfig.minimumFreeSpaceBytes];
     * `0` turns the check and the limit off.
     *
     * @since 2.2.0
     */
    public data object StorageLow : InterruptionReason

    /**
     * The platform's media service died or reported an error: Android `MEDIA_ERROR_SERVER_DIED` or
     * `MEDIA_RECORDER_ERROR_UNKNOWN`, iOS `audioRecorderEncodeErrorDidOccur`, a recorder that
     * finished unsuccessfully without being asked to stop, or an `AVAudioSession` media-services
     * reset.
     *
     * @param platformCode the platform's own code (Android `what`, or `extra` when `what` carries
     *   none) where it gave one, `null` otherwise. Diagnostic, not a stable contract.
     */
    public data class EngineDied(public val platformCode: Int? = null) : InterruptionReason
}
