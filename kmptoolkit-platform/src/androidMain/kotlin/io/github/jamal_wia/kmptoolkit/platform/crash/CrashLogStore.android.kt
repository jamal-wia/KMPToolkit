package io.github.jamal_wia.kmptoolkit.platform.crash

import android.content.Context
import java.io.File

/**
 * Creates the Android [CrashLogStore], writing one line per crash to a file in the app's private
 * storage.
 *
 * Defaults to `Context.filesDir`, which is app-private and out of other apps' reach. Override it
 * through [CrashLogConfig.directoryPath] only if you know what you are exposing — a stack trace
 * says a good deal about your app's internals.
 *
 * No permission is required for app-private storage.
 *
 * @param context any `Context`; nothing but the resolved path is retained.
 * @param config where the file lives and what it is called.
 */
public fun createCrashLogStore(
    context: Context,
    config: CrashLogConfig = CrashLogConfig(),
): CrashLogStore = FileCrashLogStore(
    File(config.directoryPath?.let(::File) ?: context.filesDir, config.fileName),
)

private class FileCrashLogStore(private val file: File) : CrashLogStore {

    override fun write(record: CrashRecord) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            file.parentFile?.mkdirs()
            file.appendText(encodeCrashRecord(record) + "\n")
        } catch (_: Throwable) {
            // Deliberate, and Throwable rather than Exception: this runs inside an
            // uncaught-exception handler, where there is no caller to report to and nothing to be
            // gained from replacing a diagnosable crash with a crash inside the crash recorder.
            // The record is lost; the original stack trace still reaches the system.
        }
    }

    override fun readAndClear(): List<CrashRecord> {
        if (!file.exists()) return emptyList()
        @Suppress("TooGenericExceptionCaught")
        val records: List<CrashRecord> = try {
            file.readLines().mapNotNull(::decodeCrashRecord)
        } catch (_: Throwable) {
            // An unreadable file is still deleted below: leaving it would mean failing the same
            // way on every launch from here on.
            emptyList()
        }
        file.delete()
        return records
    }
}
