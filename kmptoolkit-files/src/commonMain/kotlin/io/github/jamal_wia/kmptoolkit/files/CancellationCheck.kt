package io.github.jamal_wia.kmptoolkit.files

/**
 * Polled by the long-running [FileAccess] operations (recursive delete, tree size, sweep, cross-volume move);
 * when it answers `true` they stop with [FileError.Cancelled]. Boolean rather than throwing, so a caller that is
 * not a coroutine gets a typed failure; [withIo] binds it to the calling coroutine.
 *
 * @since 5.0.0
 */
public fun interface CancellationCheck {
    /** Whether the operation should stop now. */
    public fun isCancelled(): Boolean

    public companion object {
        /** A check that never cancels. */
        public val Never: CancellationCheck = CancellationCheck { false }
    }
}
