package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.files.AppDirectories
import io.github.jamal_wia.kmptoolkit.files.AppRoot
import io.github.jamal_wia.kmptoolkit.files.Backup
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult

/** How a root relates to the platform's backup. */
internal enum class BackupSupport {
    /** Never backed up, nothing to do. */
    NotBackedUp,

    /** Backed up unless the directory is marked. */
    Markable,

    /** Backed up and cannot be marked. */
    Unsupported,
}

/** [AppDirectories] over a [FileAccess]; the platform factories supply the root paths and the backup strategy. */
internal class AppDirectoriesImpl(
    private val files: FileAccess,
    private val rootPaths: (AppRoot) -> String,
    private val support: (AppRoot) -> BackupSupport,
    private val exclude: (String) -> FileResult<Unit>,
) : AppDirectories {

    override fun rootPath(root: AppRoot): String = rootPaths(root)

    override fun directory(root: AppRoot, name: String, backup: Backup): FileResult<String> {
        FileNames.requirePlainName(name)
        val base: String = rootPaths(root)
        val wantsExclusion: Boolean = backup == Backup.Excluded || root == AppRoot.NoBackup
        val mode: BackupSupport = support(root)
        if (wantsExclusion && mode == BackupSupport.Unsupported) {
            return FileResult.Failure(FileError.Unsupported(base))
        }
        val path: String = PathRules.host.join(base, name)
        val made: FileResult<Unit> = files.createDirectories(path)
        if (made is FileResult.Failure) return made
        if (wantsExclusion && mode == BackupSupport.Markable) {
            val marked: FileResult<Unit> = exclude(path)
            if (marked is FileResult.Failure) return marked
        }
        return FileResult.Success(path)
    }

    override fun excludeFromBackup(path: String): FileResult<Unit> = exclude(path)
}
