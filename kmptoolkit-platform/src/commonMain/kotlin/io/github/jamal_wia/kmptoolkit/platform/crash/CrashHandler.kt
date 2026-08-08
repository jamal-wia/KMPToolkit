package io.github.jamal_wia.kmptoolkit.platform.crash

/**
 * A handle on an installed crash handler.
 *
 * Returned by the platform `installCrashHandler` factories. An app installs one at startup and
 * never touches it again; a test installs one and uninstalls it so the next test starts clean.
 */
public interface CrashHandlerInstallation {

    /**
     * Restores whatever uncaught-exception handling was in place before this one was installed.
     *
     * Idempotent. Note that this can only restore what *this* installation replaced: if something
     * else installed a handler in between, uninstalling here would clobber it, so the
     * implementation leaves the current handler alone in that case rather than winning a race it
     * has no business entering.
     */
    public fun uninstall()
}

/**
 * Builds the record for an uncaught [throwable] — shared by both platform handlers so that a
 * crash on Android and a crash on iOS produce the same shape.
 *
 * [CrashRecord.message] falls back to the exception's class name, and then to a fixed token, so it
 * is never empty: an empty message in a crash log is indistinguishable from a broken writer.
 */
internal fun buildCrashRecord(
    throwable: Throwable,
    threadName: String,
    timestampMs: Long,
): CrashRecord = CrashRecord(
    timestampMs = timestampMs,
    threadName = threadName,
    message = throwable.message?.takeIf { it.isNotBlank() }
        ?: throwable::class.simpleName
        ?: "Unknown",
    stackTrace = throwable.stackTraceToString(),
)

/**
 * The device's wall clock, in milliseconds since the Unix epoch.
 *
 * Internal and platform-specific rather than taken from `kotlin.time.Clock`, which is still
 * experimental: an opt-in that shows up in a published library's API is a cost this module does
 * not need to pay for one timestamp.
 */
internal expect fun currentTimeMillis(): Long
