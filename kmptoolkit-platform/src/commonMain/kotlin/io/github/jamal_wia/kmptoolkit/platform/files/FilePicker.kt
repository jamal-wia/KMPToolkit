package io.github.jamal_wia.kmptoolkit.platform.files

/**
 * Opens the system file chooser and returns what the user picked, as bytes.
 *
 * Obtain one from the platform factory (`createFilePicker(context, host, config)` on Android,
 * `createFilePicker(config)` on iOS) and pass it into shared code as this interface.
 *
 * The whole point of the seam is that "let the user choose a file" is one line in shared code
 * while the platform sides are not remotely alike: Android needs an `ActivityResult` launcher
 * registered on an `Activity` before it resumes, and iOS needs a `UIDocumentPickerViewController`
 * presented from a view controller with a delegate kept alive against ARC.
 */
public interface FilePicker {

    /**
     * Presents the picker, suspends until the user chooses or dismisses it, and reads the result
     * into memory.
     *
     * Never throws for a user action or an I/O failure — cancelling is [PickResult.Cancelled] and
     * a read error is [PickResult.Failed]. It *does* honour coroutine cancellation: cancelling the
     * calling coroutine while the picker is on screen throws `CancellationException` as usual, and
     * the picker is left for the user to dismiss.
     *
     * Calls are serialized. A platform only shows one chooser at a time, so a second `pick` while
     * one is open waits for the first to finish rather than racing it.
     *
     * @param mimeTypes MIME types to filter by, e.g. `listOf("application/pdf", "image/png")`.
     *   An empty list means "any file". A type the platform cannot map (an unknown or wildcard
     *   subtype on iOS, which matches by uniform type identifier rather than by MIME) is dropped
     *   from the filter, so the picker may show more than you asked for — always check
     *   [PickedFile.mimeTypeHint] and, for anything security-relevant, the bytes themselves.
     */
    public suspend fun pick(mimeTypes: List<String> = emptyList()): PickResult
}

/**
 * Tuning for a [FilePicker].
 *
 * @property maxBytes the largest file the picker will read into memory. A file the platform
 *   reports as larger is refused as [PickResult.TooLarge] **without being read**, which is the
 *   entire reason the cap exists: `pick` returns a `ByteArray`, so a 2 GB video would otherwise be
 *   an out-of-memory kill on a low-end device rather than an error you can show. The default of
 *   25 MiB comfortably covers documents and photos. Raise it only if you know the target devices
 *   can hold the result; there is no streaming variant of this API.
 */
public data class FilePickerConfig(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }

    public companion object {
        /** 25 MiB — the default [maxBytes]. */
        public const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024
    }
}
