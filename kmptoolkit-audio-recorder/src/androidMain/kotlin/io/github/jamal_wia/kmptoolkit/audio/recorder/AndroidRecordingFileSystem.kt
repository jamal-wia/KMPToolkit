package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.exists
import java.io.File

/** [RecordingFileSystem] over `kmptoolkit-files`, rooted at the app's private storage. */
internal class AndroidRecordingFileSystem(
    private val context: Context,
    private val files: FileAccess = createFileAccess(),
) : RecordingFileSystem {

    override fun appPrivateDirectory(): String = context.filesDir.absolutePath

    override fun applicationIdentifier(): String = context.packageName

    override fun resolve(directory: String, name: String): String =
        File(directory, name).path

    override fun parentOf(path: String): String? = File(path).parent

    override fun isAbsolute(path: String): Boolean = Paths.isAbsolute(path)

    override fun ensureWritableDirectory(path: String): Boolean {
        files.createDirectories(path)
        if (files.metadata(path, followLinks = true)?.kind != FileKind.Directory) return false
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            File(path).canWrite()
        } catch (failure: Throwable) {
            // A SecurityException from a path outside the sandbox is exactly the "not writable"
            // answer this method exists to give; the caller turns it into DirectoryNotWritable.
            false
        }
    }

    override fun freeSpaceBytes(path: String): Long {
        // A path that is not an existing directory answers "unknown" (-1), not a spurious 0, so the
        // caller's treat-unknown-as-enough rule applies rather than a false InsufficientStorage.
        if (files.metadata(path, followLinks = true)?.kind != FileKind.Directory) return UNKNOWN_FREE_SPACE
        return files.freeSpaceBytes(path) ?: UNKNOWN_FREE_SPACE
    }

    override fun exists(path: String): Boolean = files.exists(path)

    override fun isRegularFile(path: String): Boolean =
        files.metadata(path, followLinks = true)?.kind == FileKind.RegularFile

    override fun delete(path: String) {
        // Best effort by contract: a file that cannot be deleted is litter, not a failure the caller can do
        // anything about, so the result is ignored.
        files.delete(path)
    }

    private companion object {
        const val UNKNOWN_FREE_SPACE = -1L
    }
}
