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

    /** Paths that already exist before the recorder runs, e.g. a reused explicit output path. */
    val existingPaths: MutableSet<String> = mutableSetOf()

    /** Raised from [ensureWritableDirectory], like a platform call that throws instead of answering. */
    var ensureWritableDirectoryFailure: Throwable? = null

    /** Runs inside [ensureWritableDirectory], before it answers and with no recorder lock held. */
    var onEnsureWritableDirectory: () -> Unit = {}

    /** Runs inside [freeSpaceBytes], before it answers and with no recorder lock held. */
    var onFreeSpaceQuery: () -> Unit = {}

    val createdDirectories: MutableSet<String> = mutableSetOf()
    val deletedPaths: MutableList<String> = mutableListOf()

    /** Paths [freeSpaceBytes] was asked about — so "the check was skipped" is assertable. */
    val freeSpaceQueries: MutableList<String> = mutableListOf()

    override fun appPrivateDirectory(): String = appDirectory

    override fun applicationIdentifier(): String = identifier

    override fun resolve(directory: String, name: String): String =
        directory.trimEnd('/') + "/" + name

    override fun parentOf(path: String): String? =
        path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    override fun ensureWritableDirectory(path: String): Boolean {
        onEnsureWritableDirectory()
        ensureWritableDirectoryFailure?.let { throw it }
        if (path in unwritableDirectories) return false
        createdDirectories += path
        return true
    }

    override fun freeSpaceBytes(path: String): Long {
        freeSpaceQueries += path
        onFreeSpaceQuery()
        return freeSpace
    }

    override fun exists(path: String): Boolean = path in existingPaths

    override fun delete(path: String) {
        deletedPaths += path
    }
}
