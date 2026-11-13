package io.github.jamal_wia.kmptoolkit.logging.overlay

/**
 * Whether a failure raised while describing a throwable (its own `toString()` or `message`) is one the overlay
 * contains: every `Exception`, plus the Errors a broken or unfinished description raises (`AssertionError`,
 * `NotImplementedError`, and on Android a `LinkageError`). Any other Error (`StackOverflowError`,
 * `OutOfMemoryError`) is not contained and propagates.
 *
 * Mirrors `isContainedSinkFailure` in kmptoolkit-logging `LoggerFactory.kt`; the two sets must stay identical
 * (`OverlayContainmentParityTest`).
 */
internal fun Throwable.isContainedDescriptionFailure(): Boolean =
    this is Exception || this is AssertionError || this is NotImplementedError || this.isLinkageError()

/** The JVM's `LinkageError` (a library version mismatch); never true where the platform has none. */
internal expect fun Throwable.isLinkageError(): Boolean
