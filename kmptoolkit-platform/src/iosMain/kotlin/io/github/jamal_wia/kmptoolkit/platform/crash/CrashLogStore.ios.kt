package io.github.jamal_wia.kmptoolkit.platform.crash

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Creates the iOS [CrashLogStore], writing one line per crash to a file in the app's container.
 *
 * Defaults to the app's Documents directory. Note that Documents is what iTunes/Finder file
 * sharing exposes if your app opts into it, and it is included in iCloud backups — set
 * [CrashLogConfig.directoryPath] to a Caches or Application Support path if either matters to you.
 *
 * No permission or entitlement is required for the app's own container.
 *
 * @param config where the file lives and what it is called.
 */
public fun createCrashLogStore(config: CrashLogConfig = CrashLogConfig()): CrashLogStore =
    IosCrashLogStore(resolvePath(config))

private fun resolvePath(config: CrashLogConfig): String? {
    val directory: String = config.directoryPath
        ?: NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).firstOrNull() as? String
        ?: return null
    return "${directory.trimEnd('/')}/${config.fileName}"
}

/**
 * @param path `null` when the platform could not name a directory at all — the store then accepts
 *   writes and drops them, rather than failing at construction inside an app's startup path.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosCrashLogStore(private val path: String?) : CrashLogStore {

    override fun write(record: CrashRecord) {
        val target: String = path ?: return
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        try {
            val line: String = encodeCrashRecord(record) + "\n"
            // Read-modify-write rather than a true append: NSString has no append-to-file, and
            // opening an NSFileHandle inside a dying process is more moving parts, not fewer. The
            // file holds a handful of lines at most, so the cost is irrelevant.
            val existing: String = NSString.stringWithContentsOfFile(
                path = target,
                encoding = NSUTF8StringEncoding,
                error = null,
            ) ?: ""
            NSString.create(string = existing + line).writeToFile(
                path = target,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
        } catch (_: Throwable) {
            // Deliberate: this runs inside the unhandled-exception hook. See the Android store.
        }
    }

    override fun readAndClear(): List<CrashRecord> {
        val target: String = path ?: return emptyList()
        val manager: NSFileManager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(target)) return emptyList()
        val content: String = NSString.stringWithContentsOfFile(
            path = target,
            encoding = NSUTF8StringEncoding,
            error = null,
        ) ?: ""
        val records: List<CrashRecord> = content.lineSequence().mapNotNull(::decodeCrashRecord).toList()
        manager.removeItemAtPath(target, error = null)
        return records
    }
}
