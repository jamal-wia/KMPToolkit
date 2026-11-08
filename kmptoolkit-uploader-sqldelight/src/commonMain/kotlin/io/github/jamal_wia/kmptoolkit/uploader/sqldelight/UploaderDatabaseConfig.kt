package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

/**
 * Which database file the standalone factories open.
 *
 * Only relevant when this module owns the file. If you hand `createUploaderStorage` a `SqlDriver` of
 * your own, the file is yours and this type never enters the picture.
 *
 * ```kotlin
 * // Default: one queue per app, filed under the app's own identifier.
 * val storage: UploaderStorage = createUploaderStorage(context)
 *
 * // Two independent queues in one app — different names, separate files, no shared rows.
 * val chat: UploaderStorage = createUploaderStorage(context, UploaderDatabaseConfig("com.example.chat"))
 * val uploads: UploaderStorage = createUploaderStorage(context, UploaderDatabaseConfig("com.example.uploads"))
 * ```
 *
 * Nothing here is hardcoded by the library, for the reason `docs/01-architecture.md` gives: a
 * library that names its own file collides the moment two libraries built on it — or two features
 * of one app — end up in the same process.
 *
 * @param name the logical queue name. `null` — the default — resolves at runtime to the consuming
 *   app's own identifier: `Context.getPackageName()` on Android, `CFBundleIdentifier` on iOS. That
 *   default is what keeps two apps from ever naming the same file, without either of them naming
 *   anything. Must not be blank when supplied.
 *
 *   The name is an identifier, not a path: it becomes part of a file name verbatim, so a path
 *   separator in it would write the queue outside the directory the platform expects.
 * @param recreateOnCorruption what to do with a file that is not a usable database (corruption, a
 *   truncated write). `false` — the default — fails the open with [UploaderStorageException] and leaves
 *   the file exactly as found, so nothing the queue still owes is lost without the app's say. `true`
 *   deletes the file and opens an empty queue instead, which is how 3.0.0 behaved: every effect that
 *   was still queued is dropped. Only a file that is recognisably corrupt is deleted; any other open
 *   failure (a failed migration, a full disk, a locked file) still fails and keeps the file.
 *   Added in 4.0.0.
 */
public data class UploaderDatabaseConfig(
    public val name: String? = null,
    public val recreateOnCorruption: Boolean = false,
) {
    /**
     * The 3.0.0 constructor, kept so code and binaries built against it keep working.
     */
    @Deprecated(
        "Superseded by the constructor with recreateOnCorruption.",
        ReplaceWith("UploaderDatabaseConfig(name, recreateOnCorruption = false)"),
        level = DeprecationLevel.HIDDEN,
    )
    public constructor(name: String?) : this(name, false)

    /**
     * The 3.0.0 `copy`, kept for binaries built against it.
     */
    @Deprecated(
        "Superseded by copy with recreateOnCorruption.",
        ReplaceWith("copy(name = name)"),
        level = DeprecationLevel.HIDDEN,
    )
    public fun copy(name: String?): UploaderDatabaseConfig = copy(name = name, recreateOnCorruption = recreateOnCorruption)

    init {
        // Validated at construction rather than surfaced as a failed operation: the name is a
        // literal a developer writes, so a wrong one is a bug to fix at the call site, not a
        // runtime condition an app can recover from. Same reasoning as StorageConfig in
        // kmptoolkit-storage.
        require(name == null || name.isNotBlank()) {
            "name must be null or a non-blank identifier, was '$name'"
        }
        require(name == null || name.none { it in FORBIDDEN_CHARACTERS }) {
            "name must not contain a path separator, a space, or a null character, was '$name'"
        }
    }

    private companion object {
        // Char(0) rather than the escape sequence: a literal NUL in a source file is invisible in
        // a diff and easy to mangle in transit.
        val FORBIDDEN_CHARACTERS: Set<Char> = setOf('/', '\\', ' ', Char(0))
    }
}

/**
 * The on-disk file name a [UploaderDatabaseConfig.name] resolves to.
 *
 * Internal on purpose: promising the exact file name in the public API would freeze the layout of
 * every consumer's data directory. It is nonetheless documented in
 * `docs/kmptoolkit-uploader-sqldelight/05-platform-notes.md`, because a consumer pulling the file off
 * a device with `adb` has to be able to find it.
 *
 * The suffix is derived from this module's own package, so a consumer whose name happens to equal
 * another database of theirs still gets a distinct file.
 */
internal fun uploaderDatabaseFileName(name: String): String = "$name.kmptoolkit.uploader.db"
