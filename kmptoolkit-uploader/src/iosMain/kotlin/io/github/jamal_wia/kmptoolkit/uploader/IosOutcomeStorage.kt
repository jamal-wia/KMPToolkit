package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSinceNow
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * [OutcomeStorage] on files in Application Support, excluded from backup and protected until the first
 * unlock after boot — a background session's completion can be delivered while the device is locked, so a
 * stricter class would make the entry unwritable exactly when it is needed.
 *
 * Each write is `NSDataWritingAtomic`: Foundation writes a temporary file and renames it over [dir]'s
 * entry, so a reader sees the previous entry, the whole new one, or nothing.
 *
 * An entry is named by [journalFileName]: the id itself when that cannot be the same file as another id's name on a
 * case-insensitive volume (every default id), an escaped form of it otherwise, so ids that differ only by letter case
 * never share an entry. An entry an earlier version wrote under an id's own spelling is still read, replayed by the
 * sweep and deleted, and is replaced by the escaped name when the id writes again (an entry whose spelling differs
 * from a kept name only by letter case is moved to its own escaped name before that kept name is written). The entry
 * of an id whose escaped name would be too long for the file system starts with the id ([idHeader]); the sweep reads it
 * from there. Foundation's own temporary files are never listed as ids, and a leftover of a dead process is purged.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosOutcomeStorage(private val dir: String) : OutcomeStorage {

    private val files: NSFileManager = NSFileManager.defaultManager

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
        if (written) legacyPathOf(itemId)?.let { files.removeItemAtPath(it, error = null) }
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
        if (!keepsOwnFileName(itemId) || name in directoryNames()) files.removeItemAtPath("$dir/$name", error = null)
        legacyPathOf(itemId)?.let { files.removeItemAtPath(it, error = null) }
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
        files.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()

    /**
     * The path of the entry of [itemId]: its [journalFileName], or, when only an entry an earlier version wrote under
     * the id's own spelling exists, that one; `null` when there is none. A kept name counts only when the directory
     * lists this exact spelling, because on a case-insensitive volume the path of `photo-1` opens the file of `Photo-1`.
     */
    private fun existingPathOf(itemId: String): String? {
        val name: String = journalFileName(itemId)
        val path = "$dir/$name"
        if (keepsOwnFileName(itemId)) return if (name in directoryNames()) path else null
        if (files.fileExistsAtPath(path)) return path
        // The legacy entry may have just been moved to its own name by a writer: look at that name once more.
        return legacyPathOf(itemId) ?: path.takeIf { files.fileExistsAtPath(it) }
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
            if (raw != null && !files.fileExistsAtPath("$dir/${journalFileName(alias)}")) {
                val moved: Boolean = write(alias, raw)
                if (!moved) return false
            } else if (raw != null) {
                files.removeItemAtPath("$dir/$alias", error = null)
            }
            if (alias in directoryNames()) return false
        }
        return true
    }

    private fun writeFile(path: String, bytes: ByteArray): Boolean {
        val data: NSData = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        }
        return data.writeToFile(path, options = WRITE_OPTIONS, error = null)
    }

    private fun readFile(path: String, extraBytes: Long = 0): ByteArray? {
        // The size first: a file larger than any entry the journal writes is not read at all, where reading it
        // would be an out-of-memory abort Kotlin/Native cannot catch.
        val attributes: Map<Any?, *> = files.attributesOfItemAtPath(path, error = null) ?: return null
        val declared: Long? = (attributes[NSFileSize] as? NSNumber)?.longLongValue
        if (declared != null && declared > MAX_ENCODED_ENTRY_BYTES + extraBytes) return ByteArray(0)
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        // A second guard for a file that grew between the two calls.
        if (data.length.toLong() > MAX_ENCODED_ENTRY_BYTES + extraBytes) return ByteArray(0)
        val size: Int = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return out
    }

    /**
     * Removes the files an atomic write left behind in a process that died, once they are older than
     * [LEFTOVER_MIN_AGE_SECONDS]: a younger one may be a write of this or another process that is still running.
     */
    override fun purgeLeftovers() {
        for (name in directoryNames()) {
            if (!isFoundationTempName(name)) continue
            val attributes: Map<Any?, *> = files.attributesOfItemAtPath("$dir/$name", error = null) ?: continue
            val modified: NSDate = attributes[NSFileModificationDate] as? NSDate ?: continue
            if (-modified.timeIntervalSinceNow > LEFTOVER_MIN_AGE_SECONDS) files.removeItemAtPath("$dir/$name", error = null)
        }
    }

    /** Creates [dir] with the protection class and the backup exclusion; idempotent. */
    private fun ensureDirectory(): Boolean {
        if (!files.fileExistsAtPath(dir)) {
            if (!files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = DIRECTORY_ATTRIBUTES, error = null)) {
                return false
            }
        }
        NSURL.fileURLWithPath(dir).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return true
    }

    companion object {

        private const val LEFTOVER_MIN_AGE_SECONDS: Double = 300.0

        private const val ASCII_LIMIT: Int = 0x80

        /** The options every entry is written with: atomic, protected until the first unlock after boot. */
        val WRITE_OPTIONS: ULong = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication

        /** The attributes the directory is created with: the same protection class. */
        val DIRECTORY_ATTRIBUTES: Map<Any?, *> = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

        /** `Application Support/kmptoolkit-uploader/outcomes`. */
        fun default(): IosOutcomeStorage {
            val base: String = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String) ?: platform.Foundation.NSTemporaryDirectory()
            return IosOutcomeStorage("$base/kmptoolkit-uploader/outcomes")
        }
    }
}

/** The outcome journal in Application Support. */
internal fun iosOutcomeJournal(logger: Logger = NoopLogger): OutcomeJournal =
    OutcomeJournal(IosOutcomeStorage.default(), UploaderClock.System, logger)

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
