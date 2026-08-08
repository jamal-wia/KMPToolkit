package io.github.jamal_wia.kmptoolkit.settings

/**
 * The smallest mutual-exclusion primitive this module needs, because Kotlin common code has none.
 *
 * It exists for one caller — [DefaultAppSettings], which persists to a store and then publishes to
 * a flow, two steps that have to happen together or the two can end up holding different values
 * for the rest of the process. The setters are not suspending functions, deliberately (a theme
 * toggle in a click handler should not need a coroutine), so a coroutine `Mutex` is not available
 * to them.
 *
 * An interface plus an `expect fun` factory, rather than an `expect class`: the same reasoning the
 * public API follows in `docs/01-architecture.md`, and it also keeps the module free of the
 * still-Beta `expect class` warning. Mirrors `NotifierLock` in `kmptoolkit-notification`, which
 * reached the same shape from the same constraint.
 */
internal interface SettingsLock {

    /** Runs [block] holding the lock, releasing it even if [block] throws. */
    fun <T> withLock(block: () -> T): T
}

/** The platform's lock: a `ReentrantLock` on Android, an `NSLock` on iOS. */
internal expect fun settingsLock(): SettingsLock
