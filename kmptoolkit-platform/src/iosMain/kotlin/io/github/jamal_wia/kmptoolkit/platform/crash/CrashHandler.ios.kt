package io.github.jamal_wia.kmptoolkit.platform.crash

import kotlin.experimental.ExperimentalNativeApi

/**
 * Installs Kotlin/Native's unhandled-exception hook so that every crash originating in Kotlin is
 * recorded into [store].
 *
 * Call it once, as early as possible in your app delegate, and read what it captured on the *next*
 * launch with [CrashLogStore.readAndClear].
 *
 * **This is not a crash reporter, and on iOS it is narrower than on Android.** It sees Kotlin
 * exceptions that reached the top of a Kotlin frame. It does **not** see Objective-C exceptions,
 * Swift runtime traps, or signals (`SIGSEGV`, `SIGABRT`) — including the abort that Kotlin/Native
 * itself raises after the hook returns. Anything in your Swift or Objective-C code needs a real
 * crash reporter.
 *
 * The previously installed hook, if any, is invoked afterwards, so an existing reporter keeps
 * working.
 *
 * @param store where records are written. It must be usable from a dying process — see
 *   [CrashLogStore].
 * @return a handle for restoring the previous hook; an app can ignore it, a test should not.
 */
@OptIn(ExperimentalNativeApi::class)
public fun installCrashHandler(store: CrashLogStore): CrashHandlerInstallation {
    // One holder per installation, filled in after the hook is built. It cannot be a captured
    // local, because `setUnhandledExceptionHook` only hands back the old hook once the new one
    // exists; and it must not be a file-level property, or a second installation would set its
    // "previous" to the first installation's own lambda — which would then read the same property
    // and call itself forever.
    val chain = PreviousHook()
    chain.hook = setUnhandledExceptionHook { throwable ->
        store.write(
            buildCrashRecord(
                throwable = throwable,
                // Kotlin/Native's hook does not name the thread it was called on, and there is no
                // portable way to ask. A fixed token beats an invented one.
                threadName = "native",
                timestampMs = currentTimeMillis(),
            ),
        )
        chain.hook?.invoke(throwable)
    }
    return IosCrashHandlerInstallation(chain)
}

@OptIn(ExperimentalNativeApi::class)
private class PreviousHook {
    var hook: ((Throwable) -> Unit)? = null
}

@OptIn(ExperimentalNativeApi::class)
private class IosCrashHandlerInstallation(
    private val chain: PreviousHook,
) : CrashHandlerInstallation {

    private var uninstalled: Boolean = false

    override fun uninstall() {
        if (uninstalled) return
        uninstalled = true
        // Kotlin/Native offers no way to ask what the current hook is, so — unlike Android — this
        // cannot check that ours is still installed. Restoring unconditionally is the only option
        // the platform gives; install once at startup and this never matters.
        setUnhandledExceptionHook(chain.hook)
    }
}
