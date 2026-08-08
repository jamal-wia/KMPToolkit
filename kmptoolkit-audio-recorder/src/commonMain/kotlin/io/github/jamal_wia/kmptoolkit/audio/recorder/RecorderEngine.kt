package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * The platform recorder, reduced to the six calls the state machine needs.
 *
 * Everything the toolkit contributes — legality of transitions, permission and storage
 * pre-checks, elapsed-time bookkeeping, typed errors — lives in [DefaultAudioRecorder] and is
 * therefore common code, tested once against a fake engine rather than three times against three
 * platform APIs. An engine implementation is only the thin part that cannot be common:
 * `MediaRecorder` and `AVAudioRecorder`.
 *
 * **Contract for implementations:** an engine may throw. [DefaultAudioRecorder] catches whatever
 * comes out and turns it into [RecorderError.EngineFailure]; an engine must not try to recover on
 * its own, must not report failure by silently doing nothing, and must never surface a
 * user-facing message.
 *
 * An engine also must not choose a dispatcher. [DefaultAudioRecorder] already invokes the blocking
 * calls below on the context its factory was given, so a `withContext` in here would override a
 * decision the consumer made.
 */
internal interface RecorderEngine {

    /** Whether `RECORD_AUDIO` is currently granted. Never requests it. */
    fun hasRecordAudioPermission(): Boolean

    /** Whether this platform can actually encode [format]. */
    fun supportsFormat(format: AudioFormat): Boolean

    /**
     * Opens the microphone and [outputPath] with [config]'s encoder settings, leaving the native
     * recorder ready to capture. Blocking, and already called on the worker context; suspending
     * only so a test double can hold the call open.
     */
    suspend fun prepare(outputPath: String, config: AudioRecorderConfig)

    /** Begins capture. Only called after a successful [prepare]. */
    fun start()

    /** Suspends capture, keeping the file open. */
    fun pause()

    /** Continues capture after [pause]. */
    fun resume()

    /** Finalizes and closes the output file. Blocking; already called on the worker context. */
    fun stop()

    /**
     * Discards the native recorder and everything it holds. Must be idempotent and must not throw
     * — it is called on the failure path of every other method, including from [AudioRecorder.release].
     */
    fun release()
}

/**
 * The filesystem operations [DefaultAudioRecorder] needs, isolated so its checks can be tested
 * without touching a real disk and so no `java.io` / `NSFileManager` type leaks into common code.
 */
internal interface RecordingFileSystem {

    /**
     * The app-private base directory recordings go under when
     * [RecordingStorage.directoryPath] is not set: `Context.getFilesDir()` on Android, the app's
     * `Documents` directory on iOS.
     */
    fun appPrivateDirectory(): String

    /**
     * The consumer's own application id / bundle identifier, used as the default subdirectory name
     * so the library hardcodes no identifier of its own.
     */
    fun applicationIdentifier(): String

    /** Joins [directory] and [name] with the platform separator. */
    fun resolve(directory: String, name: String): String

    /** The directory part of [path], or `null` if it has none. */
    fun parentOf(path: String): String?

    /**
     * Creates [path] and any missing parents, then reports whether the result is a directory that
     * can be written to. Returns `false` rather than throwing for an ordinary permission problem.
     */
    fun ensureWritableDirectory(path: String): Boolean

    /** Free bytes on the volume holding [path], or `-1` if the platform could not report it. */
    fun freeSpaceBytes(path: String): Long

    /** Deletes [path] if it exists. Never throws; a failure to delete is not worth a crash. */
    fun delete(path: String)
}

/**
 * Wall-clock milliseconds since the Unix epoch, used only to make a generated file name unique and
 * sortable. Injected so a test can pin the name a recording gets.
 */
internal fun interface EpochClock {
    fun nowMillis(): Long
}
