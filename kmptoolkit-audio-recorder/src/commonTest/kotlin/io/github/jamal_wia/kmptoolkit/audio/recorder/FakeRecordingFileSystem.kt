package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * An in-memory [RecordingFileSystem] using `/` as its separator, so path handling, directory
 * creation, free-space refusal, and deletion of partial files can all be asserted without touching
 * a real disk.
 */
internal class FakeRecordingFileSystem : RecordingFileSystem {

    var appDirectory: String = "/data/app"
    var identifier: String = "com.example.consumer"

    /** Directories that refuse to be created or written to. */
    val unwritableDirectories: MutableSet<String> = mutableSetOf()

    /** `-1` means "the platform could not tell". */
    var freeSpace: Long = Long.MAX_VALUE

    val createdDirectories: MutableSet<String> = mutableSetOf()
    val deletedPaths: MutableList<String> = mutableListOf()

    override fun appPrivateDirectory(): String = appDirectory

    override fun applicationIdentifier(): String = identifier

    override fun resolve(directory: String, name: String): String =
        directory.trimEnd('/') + "/" + name

    override fun parentOf(path: String): String? =
        path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    override fun ensureWritableDirectory(path: String): Boolean {
        if (path in unwritableDirectories) return false
        createdDirectories += path
        return true
    }

    override fun freeSpaceBytes(path: String): Long = freeSpace

    override fun delete(path: String) {
        deletedPaths += path
    }
}
