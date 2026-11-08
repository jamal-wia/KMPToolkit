package io.github.jamal_wia.kmptoolkit.downloader

/** The first 16 bytes of every SQLite database file: `SQLite format 3` and a NUL. */
private val SQLITE_HEADER: ByteArray = "SQLite format 3\u0000".encodeToByteArray()

/** A SQLite file holds at least its 100-byte header page prefix; shorter is a cut body. */
private const val SQLITE_MIN_FILE_SIZE: Long = 100L

/** How many leading bytes [hasSqliteHeader] needs. */
internal const val SQLITE_HEADER_READ_SIZE: Int = 16

/**
 * True when a file of [fileLength] bytes whose first bytes are [head] carries the SQLite header: at least 100
 * bytes long and starting with `SQLite format 3` and a NUL. Every database file, WAL mode included, has it; an
 * empty or cut body does not, and a database with no schema would only crash the host later.
 */
internal fun hasSqliteHeader(head: ByteArray, fileLength: Long): Boolean {
    if (fileLength < SQLITE_MIN_FILE_SIZE || head.size < SQLITE_HEADER.size) return false
    return SQLITE_HEADER.indices.all { index: Int -> head[index] == SQLITE_HEADER[index] }
}

/** Resolves a table or view by name, case-insensitively, as stored; the name is bound as parameter 1. */
internal const val SQLITE_RESOLVE_TABLE_SQL: String =
    "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name = ? COLLATE NOCASE LIMIT 1"

/** The meta lookup; the key is bound as parameter 1. */
internal const val SQLITE_META_VALUE_SQL: String = "SELECT value FROM meta WHERE key = ?"

/** `SELECT COUNT(*)` over [name] used as a name: quoted, every `"` doubled. */
internal fun countRowsSql(name: String): String = "SELECT COUNT(*) FROM \"${name.replace("\"", "\"\"")}\""

/** The verbatim form 3.0.0 used: [text] is SQL text. // 4.0: drop the verbatim fallback */
internal fun countRowsVerbatimSql(text: String): String = "SELECT COUNT(*) FROM $text"
