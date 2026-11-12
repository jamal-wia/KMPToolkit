package io.github.jamal_wia.kmptoolkit.files

import android.content.Context
import io.github.jamal_wia.kmptoolkit.files.internal.AppDirectoriesImpl
import io.github.jamal_wia.kmptoolkit.files.internal.BackupSupport

/**
 * The [AppDirectories] of an Android app. [AppRoot.Files] is `filesDir`, [AppRoot.NoBackup] is
 * `noBackupFilesDir`, [AppRoot.Cache] and [AppRoot.Temporary] are `cacheDir`. Android's backup rules are
 * declared per app in the manifest, so [AppRoot.Files] with [Backup.Excluded] and [AppDirectories.excludeFromBackup]
 * are [FileError.Unsupported]; use [AppRoot.NoBackup] for data that must not be backed up.
 *
 * @since 5.0.0
 */
public fun createAppDirectories(context: Context, files: FileAccess = createFileAccess()): AppDirectories {
    val app: Context = context.applicationContext ?: context
    return AppDirectoriesImpl(
        files = files,
        rootPaths = { root: AppRoot ->
            when (root) {
                AppRoot.Files -> app.filesDir.path
                AppRoot.NoBackup -> app.noBackupFilesDir.path
                AppRoot.Cache, AppRoot.Temporary -> app.cacheDir.path
            }
        },
        support = { root: AppRoot ->
            if (root == AppRoot.Files) BackupSupport.Unsupported else BackupSupport.NotBackedUp
        },
        exclude = { path: String -> FileResult.Failure(FileError.Unsupported(path)) },
    )
}
