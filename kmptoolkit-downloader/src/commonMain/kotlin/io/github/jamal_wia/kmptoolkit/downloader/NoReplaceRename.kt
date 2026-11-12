package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Moves the file [from] to [to] only when nothing exists at [to], atomically: false (and nothing
 * changed) when [to] exists or the move failed. `rename(2)`, behind `File.renameTo` and POSIX
 * `rename`, replaces its destination, so it cannot give that guarantee; both actuals are
 * `FileAccess.move` of `kmptoolkit-files` with [io.github.jamal_wia.kmptoolkit.files.ExistingFile.Fail]
 * and no cross-volume copy, which creates the new name with `link(2)` (it fails instead of replacing)
 * and then removes the old one. A crash between the two leaves both names for the same bytes, which the
 * migration's "destination exists, so drop the legacy file" rule resolves on the next pass. Used by
 * [LegacyTempMigrations] only; file systems without hard links report a failed move (logged) and the
 * legacy file stays.
 */
internal expect fun renameNoReplace(from: String, to: String): Boolean
