package io.github.jamal_wia.kmptoolkit.files

/**
 * Names of the files SQLite keeps next to a database file.
 *
 * @since 5.0.0
 */
public object SqliteFiles {
    /** [databasePath] + `-wal`, `-shm`, `-journal`, in that order. */
    public fun sidecarsOf(databasePath: String): List<String> =
        listOf("$databasePath-wal", "$databasePath-shm", "$databasePath-journal")
}
