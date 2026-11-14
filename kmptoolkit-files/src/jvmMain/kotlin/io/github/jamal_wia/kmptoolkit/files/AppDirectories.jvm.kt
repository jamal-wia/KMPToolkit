package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.AppDirectoriesImpl
import io.github.jamal_wia.kmptoolkit.files.internal.BackupSupport

/**
 * The [AppDirectories] of a desktop app: the roots are the directories given here. The desktop has no backup
 * service, so [Backup.Excluded] and [AppDirectories.excludeFromBackup] are no-ops.
 *
 * @param temporaryDirectory defaults to the `java.io.tmpdir` system property.
 * @throws IllegalArgumentException when a directory is not absolute.
 * @since 5.0.0
 */
public fun createAppDirectories(
    filesDirectory: String,
    cacheDirectory: String = filesDirectory,
    temporaryDirectory: String = System.getProperty("java.io.tmpdir"),
    files: FileAccess = createFileAccess(),
): AppDirectories {
    require(Paths.isAbsolute(filesDirectory)) { "filesDirectory must be absolute: $filesDirectory" }
    require(Paths.isAbsolute(cacheDirectory)) { "cacheDirectory must be absolute: $cacheDirectory" }
    require(Paths.isAbsolute(temporaryDirectory)) { "temporaryDirectory must be absolute: $temporaryDirectory" }
    return AppDirectoriesImpl(
        files = files,
        rootPaths = { root: AppRoot ->
            when (root) {
                AppRoot.Files, AppRoot.NoBackup -> filesDirectory
                AppRoot.Cache -> cacheDirectory
                AppRoot.Temporary -> temporaryDirectory
            }
        },
        support = { BackupSupport.NotBackedUp },
        exclude = { FileResult.Success(Unit) },
    )
}
