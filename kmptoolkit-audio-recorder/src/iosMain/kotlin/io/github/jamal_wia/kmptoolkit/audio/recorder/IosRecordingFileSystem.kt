package io.github.jamal_wia.kmptoolkit.audio.recorder

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.exists
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** [RecordingFileSystem] over `kmptoolkit-files` and `NSFileManager`, rooted at the app's `Documents` directory. */
@OptIn(ExperimentalForeignApi::class)
internal class IosRecordingFileSystem(
    private val files: FileAccess = createFileAccess(),
) : RecordingFileSystem {

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
        files.createDirectories(path)
        // A regular file at the path exists and may be writable, yet it is not a directory to record into.
        return files.metadata(path, followLinks = true)?.kind == FileKind.Directory &&
            fileManager.isWritableFileAtPath(path)
    }

    override fun freeSpaceBytes(path: String): Long {
        // A path that is not an existing directory answers "unknown" (-1), so the caller's
        // treat-unknown-as-enough rule applies.
        if (files.metadata(path, followLinks = true)?.kind != FileKind.Directory) return UNKNOWN
        return files.freeSpaceBytes(path) ?: UNKNOWN
    }

    override fun exists(path: String): Boolean = files.exists(path)

    override fun isRegularFile(path: String): Boolean =
        files.metadata(path, followLinks = true)?.kind == FileKind.RegularFile

    override fun delete(path: String) {
        // Best effort by contract. Not recursive: a non-empty directory that replaced the file is left alone.
        files.delete(path)
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
