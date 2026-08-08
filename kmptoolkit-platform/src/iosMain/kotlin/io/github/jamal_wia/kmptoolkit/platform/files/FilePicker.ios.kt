package io.github.jamal_wia.kmptoolkit.platform.files

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * Creates the iOS [FilePicker], backed by `UIDocumentPickerViewController`.
 *
 * It presents from the foreground scene's key window root view controller, so there is nothing for
 * you to register — unlike Android, where the activity-result API forces the launcher to be
 * registered on the activity. If no such window exists (the app is backgrounded, or the scene has
 * not connected yet), the call returns [PickResult.Unavailable] rather than waiting.
 *
 * No permission and no `Info.plist` usage description are required: the document picker runs out
 * of process, and the user's selection is the grant.
 *
 * @param config the size cap; see [FilePickerConfig].
 * @param logger where a missing window or a failed read is reported.
 */
public fun createFilePicker(
    config: FilePickerConfig = FilePickerConfig(),
    logger: Logger = NoopLogger,
): FilePicker = IosFilePicker(config, logger)

@OptIn(ExperimentalForeignApi::class)
private class IosFilePicker(
    private val config: FilePickerConfig,
    private val logger: Logger,
) : FilePicker {

    private val mutex = Mutex()

    /**
     * A strong reference to the delegate while the picker is on screen.
     *
     * `UIDocumentPickerViewController.delegate` is a weak reference, as UIKit delegates always
     * are, so without this the delegate would be deallocated the moment `present` returns and the
     * user's choice would arrive nowhere.
     */
    private var activeDelegate: PickerDelegate? = null

    override suspend fun pick(mimeTypes: List<String>): PickResult = mutex.withLock {
        val types: List<UTType> = mimeTypes.mapNotNull { mime -> UTType.typeWithMIMEType(mime) }
            .ifEmpty { listOf(UTTypeItem) }
        val result = CompletableDeferred<NSURL?>()
        val presented: Boolean = withContext(Dispatchers.Main) { present(types, result) }
        if (!presented) return@withLock PickResult.Unavailable
        try {
            val url: NSURL = result.await() ?: return@withLock PickResult.Cancelled
            read(url)
        } finally {
            activeDelegate = null
        }
    }

    private fun present(types: List<UTType>, result: CompletableDeferred<NSURL?>): Boolean {
        val root: UIViewController = activeRootViewController() ?: run {
            logger.w { "No key window root view controller — cannot present the document picker" }
            return false
        }
        val delegate = PickerDelegate(result)
        activeDelegate = delegate
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types)
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        root.presentViewController(picker, animated = true, completion = null)
        return true
    }

    private fun read(url: NSURL): PickResult {
        // A URL from the document picker is security-scoped: without this the read fails with a
        // permission error even though the user just chose the file.
        val scoped: Boolean = url.startAccessingSecurityScopedResource()
        try {
            declaredSize(url)?.let { size ->
                if (size > config.maxBytes) {
                    return PickResult.TooLarge(sizeBytes = size, maxBytes = config.maxBytes)
                }
            }
            val data: NSData = NSData.dataWithContentsOfURL(url)
                ?: return PickResult.Failed(cause = null)
            val length: Long = data.length.toLong()
            // Re-checked after loading: the attribute lookup above can be absent for a
            // provider-backed document, so this is the only guaranteed measurement.
            if (length > config.maxBytes) {
                return PickResult.TooLarge(sizeBytes = length, maxBytes = config.maxBytes)
            }
            val bytes = ByteArray(length.toInt())
            if (length > 0) {
                bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
            }
            val name: String = url.lastPathComponent ?: "file"
            return PickResult.Picked(
                PickedFile(name = name, mimeTypeHint = mimeTypeOf(url), bytes = bytes),
            )
        } catch (cause: Throwable) {
            logger.w(cause) { "Could not read the picked document" }
            return PickResult.Failed(cause)
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun declaredSize(url: NSURL): Long? {
        val path: String = url.path ?: return null
        val attributes: Map<Any?, *> =
            NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    /**
     * The MIME type derived from the file extension via the Uniform Type Identifier database —
     * the same lookup the system uses, rather than a hand-written extension table that would go
     * stale.
     */
    private fun mimeTypeOf(url: NSURL): String {
        val extension: String = url.pathExtension.orEmpty()
        if (extension.isEmpty()) return "application/octet-stream"
        return UTType.typeWithFilenameExtension(extension)?.preferredMIMEType
            ?: "application/octet-stream"
    }

    /**
     * The root view controller of the key window of the foreground scene.
     *
     * Walked through `connectedScenes` rather than the deprecated `UIApplication.keyWindow`, which
     * returns nothing in a multi-scene app and is unavailable on newer SDKs.
     */
    private fun activeRootViewController(): UIViewController? =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { window -> window.isKeyWindow() }
            ?.rootViewController
}

private class PickerDelegate(
    private val result: CompletableDeferred<NSURL?>,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        // `complete` on an already-completed deferred returns false rather than throwing, but
        // guarding keeps the intent explicit: UIKit may deliver a pick and a cancel.
        if (!result.isCompleted) result.complete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        if (!result.isCompleted) result.complete(null)
    }
}
