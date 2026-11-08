package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Implemented by the two shipped storages so the engine can ask whether a unit's
 * [DownloadUnit.relativePath] is usable before any transfer, without calling
 * [DownloaderStorage.getResourcePath] on a storage it does not know: a host's own storage is never
 * asked anything it was not asked in 3.0.0.
 */
internal interface RelativePathChecking {

    /** Why [unit]'s relativePath cannot be used, or null when it can. Lexical only, touches no file. */
    fun relativePathViolation(unit: DownloadUnit): InvalidDownloadUnitException.Reason?
}
