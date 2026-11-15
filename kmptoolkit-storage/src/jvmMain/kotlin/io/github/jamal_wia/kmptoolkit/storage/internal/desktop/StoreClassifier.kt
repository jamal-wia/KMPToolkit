package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

/** What is at a store file's path, decided once at the start of every operation, under the lock (plan Q5, D-2 gap closure). */
internal sealed interface FileState {

    /** No file. */
    data object Absent : FileState

    /**
     * Bytes that are not a store of this type: bad magic, an unknown version, truncated, a hostile length, a duplicate
     * or non-UTF-8 name, a kind that does not belong to this store type, or an iteration count outside the accepted
     * range. Every operation answers `Undecryptable(null)` before any OS call and before any key derivation.
     * [newerVersion] is a valid magic with a version above 1: a file of a newer library after an app downgrade, which
     * only `clear()` may touch.
     */
    class Unparsable(val newerVersion: Boolean, val reason: String) : FileState {
        override fun toString(): String = "Unparsable(newer=$newerVersion, $reason)"
    }

    /** A file of format v1 of the expected kind. */
    class Parsed(val file: ParsedStoreFile, val bytes: ByteArray) : FileState {
        override fun toString(): String = "Parsed(${file.kind}, ${file.entries.size} entries)"
    }
}

/**
 * The ONE classification step that both encrypted stores run at the start of each operation (plan Q5): the parser stays
 * pure and never reports a `StorageError`; this function decides what a parse failure means, so the two stores cannot
 * diverge. The zero-entry rule of 4.4 applies only to a [FileState.Parsed] file.
 */
internal object StoreClassifier {

    /** Classifies [bytes] (`null` = no file) for a store whose files must be of one of [kinds]. */
    fun classify(bytes: ByteArray?, kinds: Set<StoreKind>): FileState {
        if (bytes == null) return FileState.Absent
        when (val parsed: ParseResult = SecureFileFormat.parse(bytes)) {
            is ParseResult.Malformed -> return FileState.Unparsable(
                newerVersion = parsed.reason == MalformedReason.UNSUPPORTED_VERSION && hasNewerVersion(bytes),
                reason = parsed.reason.name,
            )
            is ParseResult.Parsed -> {
                val file: ParsedStoreFile = parsed.file
                if (file.kind !in kinds) return FileState.Unparsable(newerVersion = false, reason = "KIND_NOT_OF_THIS_STORE")
                val header: HeaderBody = file.header
                if (header is HeaderBody.Passphrase && !Pbkdf2.isAcceptableIterationCount(header.iterations)) {
                    return FileState.Unparsable(newerVersion = false, reason = "ITERATIONS_OUT_OF_RANGE")
                }
                return FileState.Parsed(file, bytes)
            }
        }
    }

    /** Valid magic and a version byte above [SecureFileFormat.VERSION]: written by a newer library. */
    private fun hasNewerVersion(bytes: ByteArray): Boolean {
        val magic: ByteArray = SecureFileFormat.MAGIC
        if (bytes.size <= magic.size) return false
        for (index: Int in magic.indices) if (bytes[index] != magic[index]) return false
        return (bytes[magic.size].toInt() and 0xFF) > SecureFileFormat.VERSION
    }
}
