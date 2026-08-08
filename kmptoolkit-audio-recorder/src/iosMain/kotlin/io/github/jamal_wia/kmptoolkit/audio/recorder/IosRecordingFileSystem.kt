package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** [RecordingFileSystem] over `NSFileManager`, rooted at the app's `Documents` directory. */
@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingFileSystem : RecordingFileSystem {

    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    override fun appPrivateDirectory(): String =
        NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).firstOrNull() as? String
            ?: error("The app has no Documents directory")

    override fun applicationIdentifier(): String =
        NSBundle.mainBundle.bundleIdentifier ?: FALLBACK_IDENTIFIER

    override fun resolve(directory: String, name: String): String =
        NSURL.fileURLWithPath(directory).URLByAppendingPathComponent(name)?.path
            ?: "$directory/$name"

    override fun parentOf(path: String): String? {
        // Short-circuited rather than delegated to NSURL: URLByDeletingLastPathComponent resolves a
        // bare file name against the process's working directory and hands back a real path, which
        // would silently answer "yes, it has a parent" for something the contract says has none.
        if (!path.contains('/')) return null
        return NSURL.fileURLWithPath(path).URLByDeletingLastPathComponent?.path
    }

    override fun ensureWritableDirectory(path: String): Boolean {
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createDirectoryAtPath(
                path = path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        return fileManager.fileExistsAtPath(path) && fileManager.isWritableFileAtPath(path)
    }

    override fun freeSpaceBytes(path: String): Long {
        val attributes: Map<Any?, *> =
            fileManager.attributesOfFileSystemForPath(path, error = null) ?: return UNKNOWN
        val freeSize: NSNumber = attributes[NSFileSystemFreeSize] as? NSNumber ?: return UNKNOWN
        return freeSize.longLongValue
    }

    override fun delete(path: String) {
        fileManager.removeItemAtPath(path, error = null)
    }

    private companion object {
        const val UNKNOWN = -1L

        /**
         * Only reachable in a bundle without `CFBundleIdentifier` — a unit-test host or a
         * command-line binary, never a shipped app. A recording still needs somewhere to go.
         */
        const val FALLBACK_IDENTIFIER = "recordings"
    }
}
