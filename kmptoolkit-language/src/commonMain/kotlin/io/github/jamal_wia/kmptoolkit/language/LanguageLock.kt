package io.github.jamal_wia.kmptoolkit.language

/**
 * The smallest mutual-exclusion primitive this module needs, because Kotlin common code has none.
 *
 * It exists for one caller — [AppLanguageHolder.setLanguage], which is not a suspending function and
 * so cannot take a coroutine `Mutex`, but whose steps (push onto the platform locale, compare, update
 * the flow, hand the change to the app to persist) must not interleave with another call's: two
 * interleaved calls can leave the platform locale on one language while the holder and the persisted
 * choice record the other.
 *
 * **Reentrant**, on purpose: `onLanguageChanged` is the app's code and runs while the lock is held, so
 * a callback that itself calls `setLanguage` on the same thread must not deadlock against its caller.
 *
 * An interface plus an `expect fun` factory, rather than an `expect class`, for the same reasons as
 * `kmptoolkit-notification`'s own lock: the public-API shape `docs/01-architecture.md` prescribes,
 * and no still-Beta `expect class` warning.
 */
internal interface LanguageLock {

    /** Runs [block] holding the lock, releasing it even if [block] throws. */
    fun <T> withLock(block: () -> T): T
}

/** The platform's reentrant lock: a `ReentrantLock` on Android and desktop, an `NSRecursiveLock` on iOS. */
internal expect fun languageLock(): LanguageLock
