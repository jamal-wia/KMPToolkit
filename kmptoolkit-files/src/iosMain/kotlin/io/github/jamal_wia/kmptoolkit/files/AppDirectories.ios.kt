package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.AppDirectoriesImpl
import io.github.jamal_wia.kmptoolkit.files.internal.BackupSupport
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * The [AppDirectories] of an iOS app. [AppRoot.Files] and [AppRoot.NoBackup] are Application Support (not Documents,
 * which is user-visible with file sharing), [AppRoot.Cache] is Caches, [AppRoot.Temporary] is `NSTemporaryDirectory()`.
 * [Backup.Excluded] and [AppRoot.NoBackup] mark each created directory with `NSURLIsExcludedFromBackupKey`.
 *
 * @since 5.0.0
 */
public fun createAppDirectories(files: FileAccess = createFileAccess()): AppDirectories =
    AppDirectoriesImpl(
        files = files,
        rootPaths = { root: AppRoot ->
            when (root) {
                AppRoot.Files, AppRoot.NoBackup -> searchPath(NSApplicationSupportDirectory)
                AppRoot.Cache -> searchPath(NSCachesDirectory)
                AppRoot.Temporary -> NSTemporaryDirectory().trimEnd('/')
            }
        },
        support = { root: AppRoot ->
            if (root == AppRoot.Files || root == AppRoot.NoBackup) BackupSupport.Markable else BackupSupport.NotBackedUp
        },
        exclude = { path: String -> excludePathFromBackup(files, path) },
    )

private fun searchPath(directory: ULong): String =
    (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: error("The system returned no directory of kind $directory")

@OptIn(ExperimentalForeignApi::class)
private fun excludePathFromBackup(files: FileAccess, path: String): FileResult<Unit> {
    if (!files.exists(path)) return FileResult.Failure(FileError.NotFound(path))
    val marked: Boolean = NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    return if (marked) FileResult.Success(Unit) else FileResult.Failure(FileError.Io(path))
}
