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
        val options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
        return data.writeToFile("$dir/$itemId", options = options, error = null)
    }

    override fun read(itemId: String): ByteArray? {
        val data: NSData = NSData.dataWithContentsOfFile("$dir/$itemId") ?: return null
        val size: Int = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return out
    }

    override fun delete(itemId: String) {
        files.removeItemAtPath("$dir/$itemId", error = null)
    }

    override fun itemIds(): List<String> =
        files.contentsOfDirectoryAtPath(dir, error = null).orEmpty().filterIsInstance<String>().sorted()

    /** Atomic writes leave nothing behind that this code owns; the system cleans its own temporary file. */
    override fun purgeLeftovers() = Unit

    /** Creates [dir] with the protection class and the backup exclusion; idempotent. */
    private fun ensureDirectory(): Boolean {
        if (!files.fileExistsAtPath(dir)) {
            val attributes: Map<Any?, *> = mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
            if (!files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = attributes, error = null)) {
                return false
            }
        }
        NSURL.fileURLWithPath(dir).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return true
    }

    companion object {

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
