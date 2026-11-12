package io.github.jamal_wia.kmptoolkit.files

/**
 * The app's private roots and named directories under them, with platform backup handling.
 * Get one from the platform's `createAppDirectories`.
 *
 * @since 5.0.0
 */
public interface AppDirectories {
    /** The absolute path of [root]. Does not create it. */
    public fun rootPath(root: AppRoot): String

    /**
     * Creates `<root>/<name>` (and the root) if missing and returns its path; idempotent and safe from many threads.
     * A directory it creates is durable against power loss before the path is returned.
     * [Backup.Excluded] marks the DIRECTORY (iOS NSURLIsExcludedFromBackupKey, re-applied on every call), so files
     * replaced atomically inside it stay excluded. [AppRoot.NoBackup] is always excluded. [AppRoot.Cache] and
     * [AppRoot.Temporary] are never backed up, so Excluded is a no-op there. Android [AppRoot.Files] + Excluded is
     * [FileError.Unsupported] and creates nothing. The desktop JVM has no backup service: Excluded is a no-op.
     * @throws IllegalArgumentException when [name] is not [FileNames.isPlainName].
     */
    public fun directory(root: AppRoot, name: String, backup: Backup = Backup.Included): FileResult<String>

    /** iOS: excludes the existing entry at [path] from backup (NotFound if absent). Android: Unsupported. JVM: no-op Success. */
    public fun excludeFromBackup(path: String): FileResult<Unit>
}

/**
 * One of the app's private roots.
 *
 * @since 5.0.0
 */
public enum class AppRoot {
    /** Durable app data, backed up where the platform backs up. */
    Files,

    /** Durable app data that is never backed up. */
    NoBackup,

    /** Data the system may purge to free space. */
    Cache,

    /** Short-lived scratch files. */
    Temporary,
}

/**
 * Whether a directory created by [AppDirectories.directory] is part of the platform's backup.
 *
 * @since 5.0.0
 */
public enum class Backup {
    /** The platform default: backed up where it backs up. */
    Included,

    /** Marked as excluded from backup. */
    Excluded,
}
