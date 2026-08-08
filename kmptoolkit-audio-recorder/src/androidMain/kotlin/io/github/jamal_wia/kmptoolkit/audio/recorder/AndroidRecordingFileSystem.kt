package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import java.io.File

/** [RecordingFileSystem] over `java.io.File`, rooted at the app's private storage. */
internal class AndroidRecordingFileSystem(
    private val context: Context,
) : RecordingFileSystem {

    override fun appPrivateDirectory(): String = context.filesDir.absolutePath

    override fun applicationIdentifier(): String = context.packageName

    override fun resolve(directory: String, name: String): String =
        File(directory, name).path

    override fun parentOf(path: String): String? = File(path).parent

    override fun ensureWritableDirectory(path: String): Boolean {
        val directory = File(path)
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            if (!directory.exists()) directory.mkdirs()
            directory.isDirectory && directory.canWrite()
        } catch (failure: Throwable) {
            // A SecurityException from a path outside the sandbox is exactly the "not writable"
            // answer this method exists to give; the caller turns it into DirectoryNotWritable.
            false
        }
    }

    override fun freeSpaceBytes(path: String): Long {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        return try {
            val directory = File(path)
            // usableSpace answers 0 — indistinguishable from a genuinely full volume — for a path
            // that does not exist. Report that as "unknown" instead, so the caller's
            // treat-unknown-as-enough rule applies rather than a spurious InsufficientStorage.
            if (directory.exists()) directory.usableSpace else UNKNOWN_FREE_SPACE
        } catch (failure: Throwable) {
            UNKNOWN_FREE_SPACE
        }
    }

    override fun delete(path: String) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            File(path).delete()
        } catch (failure: Throwable) {
            // Best effort by contract: a file that cannot be deleted is litter, not a failure the
            // caller can do anything about.
        }
    }

    private companion object {
        const val UNKNOWN_FREE_SPACE = -1L
    }
}
