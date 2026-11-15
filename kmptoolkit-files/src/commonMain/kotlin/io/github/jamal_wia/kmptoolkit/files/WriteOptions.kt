package io.github.jamal_wia.kmptoolkit.files

/**
 * How [FileAccess.writeAtomically] writes.
 *
 * @property durability How much of the write survives power loss; see [Durability].
 * @property existing What to do when something is already at the path; see [ExistingFile].
 * @property protection iOS data-protection class of the new file; ignored on Android and the JVM.
 * @property createParentDirectories Creates the parent of the path (and [tempDirectory]) first.
 * @property tempDirectory Where the temporary file goes; must be on the same volume as the path. `null` = the
 *   path's own directory.
 * @property tempPrefix Prefix of the temporary file's name; `null` = [FileAccessConfig.tempPrefix]. Same rules as that (1..232 UTF-8 bytes, so every temporary name fits 255 bytes).
 * @property ownerOnly `true`: the written file is readable and writable by its owner only, and so is every directory
 *   [createParentDirectories] creates for it (including [tempDirectory]). It is set when the temporary file is created,
 *   before the rename, so the final name never exists with wider access, and an external `chmod` of the old file is
 *   undone by the next write. POSIX (Linux, macOS desktop JVM): file mode 0600, directories 0700. Windows: an explicit DACL
 *   that grants only the current user and SYSTEM, with no inherited entry at creation (it is not protected against a later
 *   re-propagation from the parent). Ignored, never an error, on a volume that stores neither (FAT, exFAT, some network
 *   shares: the request is passed and the volume disregards it), on Android and on iOS, whose app sandbox already hides
 *   the files. A directory that existed before the call is never changed. It protects reading, not replacement: a
 *   writable existing parent still lets others rename over the file, and a cross-volume `move` or `copy` of the file
 *   makes its new temporary file with the default access. Default `false`: the process umask or the parent's inherited
 *   ACL decides.
 *
 *   Adding this trailing field with a default is source-compatible for named and positional callers; in the binary
 *   interface it replaces the constructor, `copy` and `copy$default` and adds `component7`, so a library compiled against
 *   5.0.0 that builds or copies a `WriteOptions` must be recompiled.
 * @throws IllegalArgumentException when [tempPrefix] breaks the rules of [FileAccessConfig.tempPrefix].
 * @since 5.0.0 (`ownerOnly` since 6.0.0)
 */
public data class WriteOptions(
    public val durability: Durability = Durability.Synced,
    public val existing: ExistingFile = ExistingFile.Replace,
    public val protection: FileProtection = FileProtection.Default,
    public val createParentDirectories: Boolean = false,
    public val tempDirectory: String? = null,
    public val tempPrefix: String? = null,
    public val ownerOnly: Boolean = false,
) {
    init {
        if (tempPrefix != null) requireTempPrefix(tempPrefix, "tempPrefix")
    }
}

/**
 * How durable a [FileAccess.writeAtomically] is.
 *
 * @since 5.0.0
 */
public enum class Durability {
    /** No sync: atomic against a process crash, not against power loss (the new name may point at an empty file). */
    Buffered,

    /** The file is synced before the rename: after power loss the path holds old or new content, never a torn file. */
    Synced,

    /**
     * Synced, plus the directory entries after the rename (iOS: F_FULLFSYNC), including the entry of every directory
     * this call created for [WriteOptions.createParentDirectories]: once Success, the new content survives power loss,
     * provided the directories on the path that existed before the call are durable: created with
     * `createDirectories(durable = true)`, by `AppDirectories`, by an earlier `SyncedWithDirectory` write, or present
     * long enough for the system to have written them back.
     */
    SyncedWithDirectory,
}

/**
 * What to do when something is already at the destination.
 *
 * @since 5.0.0
 */
public enum class ExistingFile {
    /** Atomically replace a non-directory destination. */
    Replace,

    /** Never replace anything: [FileError.AlreadyExists]. */
    Fail,
}

/**
 * The iOS data-protection class of a newly created file; ignored on Android and the JVM.
 *
 * @since 5.0.0
 */
public enum class FileProtection {
    /** The platform default for the app. */
    Default,

    /** NSFileProtectionNone. */
    None,

    /** NSFileProtectionComplete. */
    Complete,

    /** NSFileProtectionCompleteUnlessOpen. */
    CompleteUnlessOpen,

    /** NSFileProtectionCompleteUntilFirstUserAuthentication. */
    CompleteUntilFirstUserAuthentication,
}
