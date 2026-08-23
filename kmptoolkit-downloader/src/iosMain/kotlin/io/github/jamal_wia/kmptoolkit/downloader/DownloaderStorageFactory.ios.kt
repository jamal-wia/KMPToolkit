package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger

/**
 * Creates the shipped iOS [DownloaderStorage]: plain files under
 * `Application Support/<config.baseDirectoryName>/`, a hand-written streaming ZIP extractor over
 * zlib (Kotlin/Native has no `java.util.zip`), and `androidx.sqlite`'s bundled driver for
 * [ResourceFormat.SqliteDatabase] integrity checks.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a `Context` and
 * iOS needs nothing — see `docs/01-architecture.md`'s "platform factories, not `expect fun`"
 * convention. Shared code takes [DownloaderStorage] and never names this function; only your
 * platform entry point does, alongside your own [BackgroundResourceDownloader].
 *
 * @param config which directory to store resources under — see [DownloaderStorageConfig].
 * @param logger where storage operations (extraction, commit, deletion) are reported. Defaults to
 *   [NoopLogger].
 */
public fun createDownloaderStorage(
    config: DownloaderStorageConfig = DownloaderStorageConfig(),
    logger: Logger = NoopLogger,
): DownloaderStorage = IosDownloaderStorage(
    config = config,
    logger = logger,
)
