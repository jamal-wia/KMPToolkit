package io.github.jamal_wia.kmptoolkit.platform.crash

/**
 * Where [CrashRecord]s are kept between the crash that produced them and the next app launch.
 *
 * Obtain the on-disk implementation from the platform factory
 * (`createCrashLogStore(context, config)` on Android, `createCrashLogStore(config)` on iOS), or
 * substitute your own — an in-memory one ships in `kmptoolkit-platform-testing`.
 *
 * **[write] runs inside a dying process.** By the time it is called the app has an uncaught
 * exception on the stack and may have milliseconds left. An implementation must therefore be
 * synchronous, must not start a coroutine, must not touch a dependency-injection container, and
 * must not throw — there is nobody left to catch it.
 */
public interface CrashLogStore {

    /**
     * Appends [record] synchronously.
     *
     * Appends rather than replaces: an app can die twice before it next gets a chance to read, and
     * the first crash is usually the interesting one.
     *
     * Never throws. If the write fails — no space, no permission, the file went away — the record
     * is lost silently, because the alternative is throwing from inside an uncaught-exception
     * handler and turning a diagnosable crash into an undiagnosable one.
     */
    public fun write(record: CrashRecord)

    /**
     * Returns everything recorded since the last call and clears the store.
     *
     * Read-and-clear is one operation on purpose: two operations invite a caller to read, fail to
     * clear, and report the same crash on every launch forever.
     *
     * @return the records in the order they were written, or an empty list when there are none —
     *   never `null`. A record that cannot be parsed is skipped rather than failing the batch, so
     *   a half-written last line from a process killed mid-write costs you that line and nothing
     *   else.
     */
    public fun readAndClear(): List<CrashRecord>
}

/**
 * Where and under what name a platform [CrashLogStore] keeps its file.
 *
 * Both values are configurable rather than fixed, per `docs/01-architecture.md`: a library that
 * hardcodes a filename collides with the next version of itself, and with any other consumer of
 * the same app-private directory.
 *
 * @property fileName the file's name inside [directoryPath]. The default is namespaced to this
 *   toolkit so it cannot collide with a file the app owns.
 * @property directoryPath an absolute directory path, or `null` to use the platform default —
 *   `Context.filesDir` on Android, the app's Documents directory on iOS. Both are app-private.
 *   Point it somewhere world-readable and you will be publishing your own stack traces.
 */
public data class CrashLogConfig(
    val fileName: String = DEFAULT_FILE_NAME,
    val directoryPath: String? = null,
) {
    init {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
    }

    public companion object {
        /** `"kmptoolkit_crash_log.txt"` — the default [fileName]. */
        public const val DEFAULT_FILE_NAME: String = "kmptoolkit_crash_log.txt"
    }
}
