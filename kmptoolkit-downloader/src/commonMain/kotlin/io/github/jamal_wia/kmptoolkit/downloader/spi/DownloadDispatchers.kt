package io.github.jamal_wia.kmptoolkit.downloader.spi

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The dispatchers the engine runs its own work on. A two-property port rather than a dependency on
 * a particular application-wide dispatcher type, so this library stays free of any module that
 * happens to declare one — a host binds a one-line adapter over whatever it already has.
 *
 * Its real job is testability: a test substitutes a scheduler-backed dispatcher and drives the
 * engine on virtual time instead of waiting out real stall timeouts.
 */
public interface DownloadDispatchers {

    /** Blocking file and network work. */
    public val io: CoroutineDispatcher

    /** CPU-bound work — archive extraction, integrity checks. */
    public val default: CoroutineDispatcher

    public companion object {
        /** The obvious default: [Dispatchers.IO] and [Dispatchers.Default] verbatim. */
        public val Default: DownloadDispatchers = object : DownloadDispatchers {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
        }
    }
}
