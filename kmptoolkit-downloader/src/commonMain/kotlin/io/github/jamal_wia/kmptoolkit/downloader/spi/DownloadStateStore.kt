package io.github.jamal_wia.kmptoolkit.downloader.spi

/**
 * The small amount of engine state that has to outlive the process.
 *
 * One thing needs it today: the per-unit stall counter — a download that dies without transferring
 * a byte must not be retried forever, and the count is worthless if it resets every time the
 * process does.
 *
 * A port rather than a direct dependency on any particular key-value storage, so this library stays
 * free of that choice and of the dependency-injection framework it might carry. A host's adapter is
 * a handful of delegating lines — see `docs/kmptoolkit-downloader/05-platform-notes.md` for a
 * `kmptoolkit-storage`-backed one. The library owns the key names, so nothing about where or how the
 * values are stored leaks into the engine.
 *
 * No default is offered by [io.github.jamal_wia.kmptoolkit.downloader.createDownloader]: an
 * in-memory implementation would silently defeat the one guarantee this port exists for.
 */
public interface DownloadStateStore {

    public fun readInt(key: String, default: Int): Int

    public fun writeInt(key: String, value: Int)

    public fun remove(key: String)
}
