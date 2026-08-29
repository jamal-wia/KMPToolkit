package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A process-wide slot holding the running [Downloader], for code the operating system constructs
 * outside your object graph.
 *
 * A foreground service restarted by the system, or a broadcast receiver woken by a notification
 * button, is instantiated outside any dependency graph — it cannot be injected, and it may exist
 * before the host has finished building the downloader. This registry is the meeting point: the
 * host publishes the downloader once ([register]), and those entry points await it ([await]).
 *
 * Deliberately not a dependency-injection lookup: this library declares no DI framework, so it
 * cannot ask one for the downloader, and a host that wires its graph differently still works. The
 * same shape `kmptoolkit-uploader`'s `UploaderEngineRegistry` uses for its own wake entry points.
 *
 * ## Usage
 *
 * ```kotlin
 * val downloader: Downloader = createDownloader(...)
 * DownloaderRegistry.register(downloader)
 * ```
 *
 * A platform entry point then waits for it, tolerating the case where the OS got there before your
 * bootstrap finished:
 *
 * ```kotlin
 * val downloader: Downloader = DownloaderRegistry.await(10.seconds)
 *     ?: return // startup has not produced one; the platform should retry later
 * ```
 *
 * ## Contract
 *
 * - **One slot.** Registering again replaces whatever was there — a test relies on this, and an app
 *   process legitimately does it after its graph is rebuilt.
 * - **Registration is explicit.** [createDownloader] never registers on your behalf — a library that
 *   installs itself into global state behind your back is impossible to reason about in a test.
 * - Safe to call from any thread.
 */
public object DownloaderRegistry {

    private val slot: MutableStateFlow<Downloader?> = MutableStateFlow(null)

    /** The currently registered [Downloader], or `null` before any registration. */
    public val current: StateFlow<Downloader?> = slot.asStateFlow()

    /**
     * Publishes [downloader] for OS-created entry points to find. Called once by the host while it
     * builds its object graph; calling it again replaces the reference.
     */
    public fun register(downloader: Downloader) {
        slot.value = downloader
    }

    /**
     * Awaits the registered [Downloader], giving up after [timeout] and returning `null`.
     *
     * The wait exists because an OS-created entry point genuinely races the host's startup: a
     * service restarted by the platform can be constructed before the application's own
     * initialization has published anything. The timeout exists because that wait must not be
     * unbounded — an entry point that never gets a downloader has to give up and let the OS retry
     * rather than hang holding a foreground notification.
     */
    public suspend fun await(timeout: Duration = DEFAULT_AWAIT_TIMEOUT): Downloader? =
        slot.value ?: withTimeoutOrNull(timeout) { slot.filterNotNull().first() }

    /** Drops the published downloader. For tests, and for a host tearing its graph down. */
    public fun clear() {
        slot.value = null
    }

    private val DEFAULT_AWAIT_TIMEOUT: Duration = 10.seconds
}
