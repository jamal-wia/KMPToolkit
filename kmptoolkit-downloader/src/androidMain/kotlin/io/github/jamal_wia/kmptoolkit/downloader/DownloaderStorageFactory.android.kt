package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger

/**
 * Creates the shipped Android [DownloaderStorage]: plain files under
 * `filesDir/<config.baseDirectoryName>/`, `java.util.zip` extraction with zip-slip protection, and
 * `android.database.sqlite` integrity checks for a [ResourceFormat.SqliteDatabase] unit.
 *
 * The factory is per-platform rather than `expect`/`actual` because Android needs a [Context] and
 * iOS needs nothing — see `docs/01-architecture.md`'s "platform factories, not `expect fun`"
 * convention. Shared code takes [DownloaderStorage] and never names this function; only your
 * platform entry point does, alongside your own [BackgroundResourceDownloader].
 *
 * @param context any `Context`. Only its application context is retained, so passing an `Activity`
 *   cannot leak it.
 * @param config which directory to store resources under — see [DownloaderStorageConfig].
 * @param logger where storage operations (extraction, commit, deletion) are reported. Defaults to
 *   [NoopLogger].
 */
public fun createDownloaderStorage(
    context: Context,
    config: DownloaderStorageConfig = DownloaderStorageConfig(),
    logger: Logger = NoopLogger,
): DownloaderStorage = AndroidDownloaderStorage(
    context = context.applicationContext,
    config = config,
    logger = logger,
)
