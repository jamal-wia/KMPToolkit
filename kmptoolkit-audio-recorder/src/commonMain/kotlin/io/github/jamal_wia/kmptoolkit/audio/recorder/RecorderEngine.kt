package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * What an engine reports on its own, while nobody is calling it. Raw: an engine says what the
 * platform told it and decides nothing — whether it ends the recording, after which debounce,
 * with what outcome, is [DefaultAudioRecorder]'s business, so those decisions are common code
 * tested once.
 */
internal sealed interface EngineEvent {

    /**
     * The platform ended the recording, or broke it beyond use, for [reason]. The first one of a
     * session counts; the engine need not filter duplicates.
     */
    data class Interrupted(val reason: InterruptionReason) : EngineEvent

    /**
     * The platform started ([silenced] `true`) or stopped ([silenced] `false`) silencing the
     * input while the recorder keeps running. Debounced by [DefaultAudioRecorder]; an engine
     * reports every change, and also the state right after [RecorderEngine.start] and
     * [RecorderEngine.resume] — a recording that begins silenced is reported as `true`.
     */
    data class InputSilenced(val silenced: Boolean) : EngineEvent
}

/**
 * The platform recorder, reduced to the calls the state machine needs.
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
 * **Events.** An engine reports what the platform does on its own through the listener given to
 * [setEventListener], as [EngineEvent]s. It may call the listener from any thread, including
 * synchronously from inside one of its own methods, and while holding locks of its own — the
 * listener never blocks and never calls back into the engine on the same stack. An engine must
 * remove every listener and observer it registered in [release] and before a normal [stop], so a
 * recording the app ended is never reported as one the system ended.
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
     *
     * @param maxFileSizeBytes an upper bound on the output file size the platform should enforce
     *   as a backstop against a full disk, or `null` for none. Honoured where the platform has
     *   such a limit (Android's `setMaxFileSize`), ignored where it has not.
     */
    suspend fun prepare(
        outputPath: String,
        config: AudioRecorderConfig,
        maxFileSizeBytes: Long? = null,
    )

    /**
     * Registers (or, with `null`, removes) the listener platform events are reported to. Called
     * after a successful [prepare] and with `null` when a recording ends, quickly and without
     * I/O. A listener replaced by another or by `null` must receive nothing more.
     */
    fun setEventListener(listener: ((EngineEvent) -> Unit)?)

    /**
     * Begins capture. Only called after a successful [prepare]. An engine that can tell reports
     * [EngineEvent.InputSilenced] when the input is already silenced once capture has begun.
     */
    fun start()

    /** Suspends capture, keeping the file open. */
    fun pause()

    /** Continues capture after [pause]; see [start] for the silenced-input report. */
    fun resume()

    /**
     * Loudest input since the previous call, in dBFS (`<= 0`, [Float.NEGATIVE_INFINITY] for digital
     * silence), or `null` when nothing is recording or the platform could not answer. The first call
     * after capture begins has no previous call to measure from, so its value covers an unspecified
     * stretch and is meant to be discarded.
     *
     * Unlike the other calls this one must never throw: it runs on the metering coroutine,
     * concurrently with transitions on the caller's thread, and may land just after the native
     * recorder was paused, stopped or released.
     */
    fun peakDbfs(): Float?

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
