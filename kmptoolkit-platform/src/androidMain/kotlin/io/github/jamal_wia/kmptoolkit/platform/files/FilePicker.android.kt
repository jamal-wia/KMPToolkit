package io.github.jamal_wia.kmptoolkit.platform.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What `OpenDocument` shows when no MIME filter is given. */
private const val ANY_MIME_TYPE: String = "*/*"

/**
 * The Activity-side half of the Android file picker: it launches the chooser and reports back the
 * `Uri` the user selected.
 *
 * You implement this, in ten lines, on top of `ActivityResultContracts.OpenDocument`. It is not
 * done for you because doing it would mean depending on `androidx.activity` and, worse, holding an
 * `Activity` inside a library object whose lifetime the library controls — an
 * `ActivityResultLauncher` must be registered before the activity resumes and dies with it, which
 * is precisely the coupling this module refuses to hide. Registering it yourself keeps the
 * activity reference inside the activity, where the framework already manages it.
 *
 * ```kotlin
 * class MainActivity : ComponentActivity(), FilePickerHost {
 *     private var pending: ((Uri?) -> Unit)? = null
 *     private val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
 *         pending?.invoke(uri)
 *         pending = null
 *     }
 *
 *     override fun launch(mimeTypes: Array<String>, onResult: (Uri?) -> Unit): Boolean {
 *         pending = onResult
 *         return runCatching { launcher.launch(mimeTypes) }.isSuccess
 *     }
 * }
 * ```
 */
public interface FilePickerHost {

    /**
     * Shows the system chooser filtered to [mimeTypes] and later invokes [onResult].
     *
     * @param onResult must be called exactly once: with the chosen `Uri`, or with `null` when the
     *   user dismissed the chooser. Never calling it leaves the caller's coroutine suspended until
     *   it is cancelled.
     * @return `false` if the chooser could not be shown at all — the activity is gone, no app on
     *   the device handles `OpenDocument`. [onResult] must then **not** be called, and the picker
     *   reports [PickResult.Unavailable].
     */
    public fun launch(mimeTypes: Array<String>, onResult: (Uri?) -> Unit): Boolean
}

/**
 * Creates the Android [FilePicker].
 *
 * The chooser itself is [host]'s job; everything after the user taps a file — resolving the
 * display name, enforcing [FilePickerConfig.maxBytes] before a single byte is read, and reading
 * the content through a `ContentResolver` — happens here.
 *
 * No permission is required. `OpenDocument` grants read access to exactly the file the user chose,
 * which is why this module needs neither `READ_EXTERNAL_STORAGE` nor `READ_MEDIA_*`; see
 * `docs/kmptoolkit-platform/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param host the activity-side launcher. Hold this picker no longer than you hold the host.
 * @param config the size cap; see [FilePickerConfig].
 * @param logger where a refused launch or a failed read is reported.
 */
public fun createFilePicker(
    context: Context,
    host: FilePickerHost,
    config: FilePickerConfig = FilePickerConfig(),
    logger: Logger = NoopLogger,
): FilePicker = AndroidFilePicker(context.applicationContext, host, config, logger)

private class AndroidFilePicker(
    private val context: Context,
    private val host: FilePickerHost,
    private val config: FilePickerConfig,
    private val logger: Logger,
) : FilePicker {

    /** Android shows one chooser at a time; a second `pick` waits rather than racing the first. */
    private val mutex = Mutex()

    override suspend fun pick(mimeTypes: List<String>): PickResult = mutex.withLock {
        val filter: Array<String> =
            mimeTypes.takeIf { it.isNotEmpty() }?.toTypedArray() ?: arrayOf(ANY_MIME_TYPE)
        val result = CompletableDeferred<Uri?>()
        val launched: Boolean = runCatching {
            host.launch(filter) { uri -> result.complete(uri) }
        }.getOrElse { cause ->
            logger.w(cause) { "The file picker host threw while launching" }
            false
        }
        if (!launched) return@withLock PickResult.Unavailable
        val uri: Uri = result.await() ?: return@withLock PickResult.Cancelled
        read(uri)
    }

    private fun read(uri: Uri): PickResult {
        val resolver: ContentResolver = context.contentResolver
        val metadata: UriMetadata = resolver.readMetadata(uri)
        // Checked before opening a stream: the whole point of the cap is that an oversized file is
        // never held in memory, and reading first to measure would defeat it.
        if (metadata.size != null && metadata.size > config.maxBytes) {
            return PickResult.TooLarge(sizeBytes = metadata.size, maxBytes = config.maxBytes)
        }
        return runCatching {
            val bytes: ByteArray = resolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                ?: return@runCatching PickResult.Failed(cause = null)
            // Re-checked after reading: a provider may report no size at all, or lie about it.
            if (bytes.size.toLong() > config.maxBytes) {
                PickResult.TooLarge(sizeBytes = bytes.size.toLong(), maxBytes = config.maxBytes)
            } else {
                PickResult.Picked(
                    PickedFile(
                        name = metadata.name,
                        mimeTypeHint = metadata.mimeType ?: resolver.getType(uri).orEmpty(),
                        bytes = bytes,
                    ),
                )
            }
        }.getOrElse { cause ->
            logger.w(cause) { "Could not read the picked document" }
            PickResult.Failed(cause)
        }
    }

    private data class UriMetadata(val name: String, val mimeType: String?, val size: Long?)

    /**
     * Reads `OpenableColumns` from the content provider.
     *
     * Every column is optional — a provider is free to return no cursor, no rows, or null cells —
     * so each is defended individually and the name falls back to the URI's last path segment.
     */
    private fun ContentResolver.readMetadata(uri: Uri): UriMetadata {
        var name: String = uri.lastPathSegment ?: "file"
        var mimeType: String? = null
        var size: Long? = null
        runCatching {
            query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)?.let { value -> name = value }
                    mimeType = cursor.stringOrNull("mime_type")
                    size = cursor.longOrNull(OpenableColumns.SIZE)
                }
            }
        }
        return UriMetadata(name = name, mimeType = mimeType, size = size)
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index: Int = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index: Int = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }
}
