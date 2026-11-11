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
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
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
 * sweep and deleted, and is replaced by the escaped name when the id writes again.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosOutcomeStorage(private val dir: String) : OutcomeStorage {

    private val files: NSFileManager = NSFileManager.defaultManager

    override fun write(itemId: String, bytes: ByteArray): Boolean {
        if (!ensureDirectory()) return false
        val data: NSData = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        }
        val written: Boolean = data.writeToFile("$dir/${journalFileName(itemId)}", options = WRITE_OPTIONS, error = null)
        // The entry an earlier version wrote under the id's own spelling is superseded by this one.
        if (written) legacyPathOf(itemId)?.let { files.removeItemAtPath(it, error = null) }
        return written
    }

    override fun read(itemId: String): ByteArray? {
        val path: String = existingPathOf(itemId)
        // The size first: a file larger than any entry the journal writes is not read at all, where reading it
        // would be an out-of-memory abort Kotlin/Native cannot catch.
        val attributes: Map<Any?, *> = files.attributesOfItemAtPath(path, error = null) ?: return null
        val declared: Long? = (attributes[NSFileSize] as? NSNumber)?.longLongValue
        if (declared != null && declared > MAX_ENCODED_ENTRY_BYTES) return ByteArray(0)
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        // A second guard for a file that grew between the two calls.
        if (data.length.toLong() > MAX_ENCODED_ENTRY_BYTES) return ByteArray(0)
        val size: Int = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return out
    }

    override fun delete(itemId: String) {
        files.removeItemAtPath("$dir/${journalFileName(itemId)}", error = null)
        legacyPathOf(itemId)?.let { files.removeItemAtPath(it, error = null) }
    }

    override fun itemIds(): List<String> =
        directoryNames().map(::itemIdOfJournalFileName).distinct().sorted()

    private fun directoryNames(): List<String> =
        files.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>()

    /**
     * The path of the entry of [itemId]: its [journalFileName], or, when only an entry an earlier version wrote under
     * the id's own spelling exists, that one.
     */
    private fun existingPathOf(itemId: String): String {
        val path = "$dir/${journalFileName(itemId)}"
        return if (files.fileExistsAtPath(path)) path else legacyPathOf(itemId) ?: path
    }

    /**
     * The path of an entry an earlier version wrote for [itemId] under its own spelling, when that spelling is not
     * the id's current file name and the directory lists exactly this spelling. The listing, not the path, decides:
     * on a case-insensitive volume the path of one id opens the file of another that differs only by case.
     */
    private fun legacyPathOf(itemId: String): String? =
        if (journalFileName(itemId) != itemId && itemId in directoryNames()) "$dir/$itemId" else null

    /** Atomic writes leave nothing behind that this code owns; the system cleans its own temporary file. */
    override fun purgeLeftovers() = Unit

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
