package io.github.jamal_wia.kmptoolkit.notification

/**
 * The smallest mutual-exclusion primitive this module needs, because Kotlin common code has none.
 *
 * It exists for one caller — [ProgressCoalescer], whose state is touched from whichever thread
 * finished a chunk of work, and from [Notifier.cancel], which is not a suspending function and so
 * cannot take a coroutine `Mutex`. A few lines of `expect`/`actual` beat either an atomics
 * dependency or a "not thread-safe, sorry" caveat on an interface people will call from a
 * background thread anyway.
 *
 * An interface plus an `expect fun` factory, rather than an `expect class`: the same reasoning the
 * public API follows in `docs/01-architecture.md`, and it also keeps the module free of the
 * still-Beta `expect class` warning.
 */
internal interface NotifierLock {

    /** Runs [block] holding the lock, releasing it even if [block] throws. */
    fun <T> withLock(block: () -> T): T
}

/** The platform's lock: a `ReentrantLock` on Android, an `NSLock` on iOS. */
internal expect fun notifierLock(): NotifierLock
