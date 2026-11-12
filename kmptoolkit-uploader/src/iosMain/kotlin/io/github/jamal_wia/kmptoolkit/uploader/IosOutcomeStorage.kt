package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSinceNow

/**
 * [OutcomeStorage] on files in Application Support, excluded from backup and protected until the first
 * unlock after boot — a background session's completion can be delivered while the device is locked, so a
 * stricter class would make the entry unwritable exactly when it is needed.
 *
 * Each write goes through `kmptoolkit-files`: the bytes are written to a temporary file in [partialDir] (a sibling of
 * [dir], so the rename stays on one volume), synced to disk and renamed over [dir]'s entry, so a reader sees the
 * previous entry, the whole new one, or nothing, and an entry that was reported written survives a power loss.
 *
 * An entry is named by [journalFileName]: the id itself when that cannot be the same file as another id's name on a
 * case-insensitive volume (every default id), an escaped form of it otherwise, so ids that differ only by letter case
 * never share an entry. An entry an earlier version wrote under an id's own spelling is still read, replayed by the
 * sweep and deleted, and is replaced by the escaped name when the id writes again (an entry whose spelling differs
 * from a kept name only by letter case is moved to its own escaped name before that kept name is written). The entry
 * of an id whose escaped name would be too long for the file system starts with the id ([idHeader]); the sweep reads it
 * from there. Foundation's own temporary files (left by 4.x) are never listed as ids, and a leftover of a dead process, in [dir] or in
 * [partialDir], is purged.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosOutcomeStorage(
    private val dir: String,
    /** Where an entry's temporary file is written before it is renamed into [dir]: a sibling, so the rename is atomic. */
    private val partialDir: String = "${dir.substringBeforeLast('/')}/$PARTIAL_DIRECTORY_NAME",
    private val files: FileAccess = createFileAccess(),
) : OutcomeStorage {

    private val manager: NSFileManager = NSFileManager.defaultManager

    override fun write(itemId: String, bytes: ByteArray): Boolean {
        if (!ensureDirectory()) return false
        val name: String = journalFileName(itemId)
        val content: ByteArray = if (name.startsWith(HASHED_NAME_PREFIX)) {
            val header: ByteArray = idHeader(itemId) ?: return false
            header + bytes
        } else {
            bytes
        }
        // A 4.0.0 entry of another id that differs from this id's own name only by letter case is the same file on a
        // case-insensitive volume: it moves to its own escaped name first, or the write would replace it.
        if (keepsOwnFileName(itemId) && !clearCaseAliases(name)) return false
        val written: Boolean = writeFile("$dir/$name", content)
        // The entry an earlier version wrote under the id's own spelling is superseded by this one.
        if (written) legacyPathOf(itemId)?.let { manager.removeItemAtPath(it, error = null) }
        return written
    }

    override fun read(itemId: String): ByteArray? {
        val path: String = existingPathOf(itemId) ?: return null
        // Only the hashed name holds an id header; a 4.0.0 entry of the same id under its own spelling does not.
        val hashed: Boolean = journalFileName(itemId).startsWith(HASHED_NAME_PREFIX) && path == "$dir/${journalFileName(itemId)}"
        if (!hashed) return readFile(path)
        return entryOf(itemId, readFile(path, MAX_HEADER_BYTES) ?: return null)
    }

    override fun delete(itemId: String) {
        val name: String = journalFileName(itemId)
        // A kept name is removed only when the directory lists this exact spelling: on a case-insensitive volume the
        // path of one id is also the path of the 4.0.0 entry of an id that differs only by case.
        if (!keepsOwnFileName(itemId) || name in directoryNames()) manager.removeItemAtPath("$dir/$name", error = null)
        legacyPathOf(itemId)?.let { manager.removeItemAtPath(it, error = null) }
    }

    override fun itemIds(): List<String> =
        directoryNames().filterNot(::isFoundationTempName).mapNotNull(::idOfFile).distinct().sorted()

    /** The id of the entry in [fileName]; `null` for a hashed entry that is gone since the listing was taken. */
    private fun idOfFile(fileName: String): String? {
        if (!fileName.startsWith(HASHED_NAME_PREFIX)) return itemIdOfJournalFileName(fileName)
        val raw: ByteArray = readFile("$dir/$fileName", MAX_HEADER_BYTES) ?: return null
        return idOfHeader(raw) ?: fileName
    }

    /**
     * A raw name an earlier version wrote for an id outside ASCII is listed the way Foundation spelled it (accents
     * decomposed) and the id may have been either: the listed spelling and its composed form.
     */
    override fun sweepSpellings(listedId: String): List<String> {
        if (listedId.startsWith(HASHED_NAME_PREFIX) || keepsOwnFileName(listedId) || isFoundationTempName(listedId)) return listOf(listedId)
        if (listedId.all { it.code < ASCII_LIMIT } || itemIdOfJournalFileName(listedId) != listedId) return listOf(listedId)
        if (listedId !in directoryNames()) return listOf(listedId)
        return listOf(listedId, (listedId as NSString).precomposedStringWithCanonicalMapping).distinct()
    }

    private fun directoryNames(): List<String> =
        manager.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()

    /**
     * The path of the entry of [itemId]: its [journalFileName], or, when only an entry an earlier version wrote under
     * the id's own spelling exists, that one; `null` when there is none. A kept name counts only when the directory
     * lists this exact spelling, because on a case-insensitive volume the path of `photo-1` opens the file of `Photo-1`.
     */
    private fun existingPathOf(itemId: String): String? {
        val name: String = journalFileName(itemId)
        val path = "$dir/$name"
        if (keepsOwnFileName(itemId)) return if (name in directoryNames()) path else null
        if (manager.fileExistsAtPath(path)) return path
        // The legacy entry may have just been moved to its own name by a writer: look at that name once more.
        return legacyPathOf(itemId) ?: path.takeIf { manager.fileExistsAtPath(it) }
    }

    /**
     * The path of an entry an earlier version wrote for [itemId] under its own spelling, when that spelling is not
     * the id's current file name, the directory lists this spelling (or one the volume treats as the same name) and it is not a name this scheme
     * produces (the escaped name of `A` is `~_~a`, which is not the 4.0.0 entry of the id `~_~a`). The listing, not the
     * path, decides: on a case-insensitive volume the path of one id opens the file of another that differs only by case.
     */
    private fun legacyPathOf(itemId: String): String? = legacyNameOf(itemId)?.let { "$dir/$it" }

    /**
     * The name under which the directory lists the entry an earlier version wrote for [itemId], or `null`. For an id
     * outside ASCII the listed name may spell the same text differently: Foundation decomposes accents when it creates
     * a file (`é` becomes `e` and a combining accent), the volume treats both spellings as one name, and an earlier
     * version found its entry again through the path. So a name that is canonically equivalent to the id counts, and
     * the exact spelling is preferred when both are listed.
     */
    private fun legacyNameOf(itemId: String): String? {
        if (keepsOwnFileName(itemId) || isFoundationTempName(itemId)) return null
        val names: List<String> = directoryNames()
        val listed: String = when {
            itemId in names -> itemId
            itemId.all { it.code < ASCII_LIMIT } -> return null
            else -> {
                val wanted: String = canonicalForm(itemId)
                names.firstOrNull { it.any { c -> c.code >= ASCII_LIMIT } && !isFoundationTempName(it) && canonicalForm(it) == wanted }
                    ?: return null
            }
        }
        return listed.takeIf { itemIdOfJournalFileName(it) == it }
    }

    private fun canonicalForm(name: String): String = (name as NSString).decomposedStringWithCanonicalMapping

    /**
     * Moves every entry whose name differs from [name] only by letter case (a 4.0.0 entry of an id with an upper-case
     * letter) to the escaped name of its own id, or deletes it when that entry already exists. Returns whether none is left.
     */
    private fun clearCaseAliases(name: String): Boolean {
        for (alias in directoryNames()) {
            if (alias == name || !alias.equals(name, ignoreCase = true) || isFoundationTempName(alias)) continue
            val aliasId: String = itemIdOfJournalFileName(alias)
            if (aliasId != alias) continue
            val raw: ByteArray? = readFile("$dir/$alias")
            if (raw != null && !manager.fileExistsAtPath("$dir/${journalFileName(alias)}")) {
                val moved: Boolean = write(alias, raw)
                if (!moved) return false
            } else if (raw != null) {
                manager.removeItemAtPath("$dir/$alias", error = null)
            }
            if (alias in directoryNames()) return false
        }
        return true
    }

    /**
     * The bytes go to a temporary file in [partialDir], are synced to disk and renamed over [path], with the
     * first-unlock protection class (see [WRITE_OPTIONS]).
     */
    private fun writeFile(path: String, bytes: ByteArray): Boolean {
        val options = WriteOptions(
            durability = Durability.Synced,
            protection = FileProtection.CompleteUntilFirstUserAuthentication,
            tempDirectory = partialDir,
        )
        return files.writeAtomically(path, bytes, options).isSuccess
    }

    private fun readFile(path: String, extraBytes: Long = 0): ByteArray? =
        when (val result: FileResult<ByteArray> = files.readBytes(path, MAX_ENCODED_ENTRY_BYTES + extraBytes)) {
            is FileResult.Success -> result.value
            // Larger than any entry the journal writes: not read at all, where reading it would be an
            // out-of-memory abort Kotlin/Native cannot catch.
            is FileResult.Failure -> if (result.error is FileError.TooLarge) ByteArray(0) else null
        }

    /**
     * Removes the files an atomic write left behind in a process that died, once they are older than
     * [LEFTOVER_MIN_AGE_SECONDS]: a younger one may be a write of this or another process that is still running.
     * Foundation's own temporary files in [dir] (4.x entries were written with `NSDataWritingAtomic`) and the
     * temporary files in [partialDir] are both swept.
     */
    override fun purgeLeftovers() {
        files.sweep(partialDir, olderThanMillis = LEFTOVER_MIN_AGE_SECONDS.toLong() * MILLIS_PER_SECOND) { true }
        for (name in directoryNames()) {
            if (!isFoundationTempName(name)) continue
            val attributes: Map<Any?, *> = manager.attributesOfItemAtPath("$dir/$name", error = null) ?: continue
            val modified: NSDate = attributes[NSFileModificationDate] as? NSDate ?: continue
            if (-modified.timeIntervalSinceNow > LEFTOVER_MIN_AGE_SECONDS) manager.removeItemAtPath("$dir/$name", error = null)
        }
    }

    /** Creates [dir] and [partialDir] with the protection class and the backup exclusion; idempotent. */
    private fun ensureDirectory(): Boolean = ensureDirectory(dir) && ensureDirectory(partialDir)

    private fun ensureDirectory(path: String): Boolean {
        if (!manager.fileExistsAtPath(path)) {
            if (!manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = DIRECTORY_ATTRIBUTES, error = null)) {
                return false
            }
        }
        NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return true
    }

    companion object {

        private const val LEFTOVER_MIN_AGE_SECONDS: Double = 300.0

        private const val MILLIS_PER_SECOND: Long = 1_000L

        /** The directory next to the entries directory that holds the temporary files of writes in flight. */
        const val PARTIAL_DIRECTORY_NAME: String = "outcomes-partial"

        private const val ASCII_LIMIT: Int = 0x80

        /**
         * The Foundation form of the protection class every entry is written with (atomic, protected until the first
         * unlock after boot). Entries are written through FileAccess now, asked for
         * [FileProtection.CompleteUntilFirstUserAuthentication]; this constant is the reference the tests compare
         * that class with.
         */
        val WRITE_OPTIONS: ULong = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication

        /** The attributes the directory is created with: the same protection class. */
        val DIRECTORY_ATTRIBUTES: Map<Any?, *> = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

        /** `Application Support/<directoryName>/outcomes`, temporary files in `<directoryName>/outcomes-partial`. */
        fun default(directoryName: String = BackgroundUploadConfig.DEFAULT_JOURNAL_DIRECTORY_NAME): IosOutcomeStorage {
            val base: String = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String) ?: platform.Foundation.NSTemporaryDirectory()
            return IosOutcomeStorage("$base/$directoryName/outcomes")
        }
    }
}

/** The outcome journal in Application Support, in the directory named [directoryName]. */
internal fun iosOutcomeJournal(
    logger: Logger = NoopLogger,
    directoryName: String = BackgroundUploadConfig.DEFAULT_JOURNAL_DIRECTORY_NAME,
): OutcomeJournal = OutcomeJournal(IosOutcomeStorage.default(directoryName), UploaderClock.System, logger)

/** Largest [idHeader]: the marker, the length and 200 UTF-16 code units, with room for a longer id of a direct caller. */
private const val MAX_HEADER_BYTES: Long = 4L + 2 + 2 * 1024

private val HEADER_MARKER: ByteArray = byteArrayOf('K'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())

/** `KID1`, the id's length in UTF-16 code units (two bytes) and the code units, high byte first; `null` when too long. */
private fun idHeader(itemId: String): ByteArray? {
    if (itemId.length > MAX_HEADER_ID_UNITS) return null
    return HEADER_MARKER + byteArrayOf((itemId.length shr 8).toByte(), itemId.length.toByte()) + utf16BigEndian(itemId)
}

private const val MAX_HEADER_ID_UNITS: Int = 1024

/** The id an entry starting with [idHeader] belongs to, or `null` when [raw] does not start with a whole header. */
private fun idOfHeader(raw: ByteArray): String? {
    if (raw.size < HEADER_MARKER.size + 2 || !HEADER_MARKER.indices.all { raw[it] == HEADER_MARKER[it] }) return null
    val units: Int = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
    if (units > MAX_HEADER_ID_UNITS || raw.size < 6 + 2 * units) return null
    return buildString {
        for (i in 0 until units) {
            append((((raw[6 + 2 * i].toInt() and 0xFF) shl 8) or (raw[7 + 2 * i].toInt() and 0xFF)).toChar())
        }
    }
}

/** The bytes the journal wrote for [itemId] in [raw], or `null` when the header names another id. */
private fun entryOf(itemId: String, raw: ByteArray): ByteArray? {
    // An empty file is the marker of an oversized entry (see readFile): the journal discards it.
    if (raw.isEmpty()) return raw
    if (idOfHeader(raw) != itemId) return null
    return raw.copyOfRange(6 + 2 * itemId.length, raw.size)
}
