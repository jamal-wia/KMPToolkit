package io.github.jamal_wia.kmptoolkit.platform.crash

/**
 * Installs an uncaught-exception handler that records every crash into [store], then hands the
 * exception to whatever handler was already installed.
 *
 * Call it once, as early as possible in `Application.onCreate` — a crash before this line is not
 * recorded — and read what it captured on the *next* launch with [CrashLogStore.readAndClear].
 *
 * **This is not a crash reporter.** It does not upload anything, it does not catch native (NDK)
 * crashes, and it cannot record a process that the system kills outright — an ANR, a low-memory
 * kill, `Runtime.halt`. It records Kotlin/Java exceptions that reached the top of a thread, which
 * is the class of crash a released app otherwise loses entirely.
 *
 * The previous handler is always invoked afterwards, so the system crash dialog, Crashlytics, or
 * anything else already installed keeps working exactly as before.
 *
 * @param store where records are written. It must be usable from a dying process — see
 *   [CrashLogStore].
 * @return a handle for restoring the previous handler; an app can ignore it, a test should not.
 */
public fun installCrashHandler(store: CrashLogStore): CrashHandlerInstallation {
    val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
    val installed = Thread.UncaughtExceptionHandler { thread, throwable ->
        store.write(
            buildCrashRecord(
                throwable = throwable,
                threadName = thread.name,
                timestampMs = currentTimeMillis(),
            ),
        )
        // Delegating is not optional: swallow it and the process keeps limping along in an
        // undefined state with no crash dialog, which is worse than the crash.
        previous?.uncaughtException(thread, throwable)
    }
    Thread.setDefaultUncaughtExceptionHandler(installed)
    return AndroidCrashHandlerInstallation(installed, previous)
}

private class AndroidCrashHandlerInstallation(
    private val installed: Thread.UncaughtExceptionHandler,
    private val previous: Thread.UncaughtExceptionHandler?,
) : CrashHandlerInstallation {

    override fun uninstall() {
        // Only if ours is still the active handler: something installed after us owns the chain
        // now, and restoring `previous` here would silently unhook it.
        if (Thread.getDefaultUncaughtExceptionHandler() === installed) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
