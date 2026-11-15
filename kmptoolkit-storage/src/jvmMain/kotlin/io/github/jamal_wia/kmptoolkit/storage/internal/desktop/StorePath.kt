package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.PathKey
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Where a store file really is, as the volume spells it: the canonical directory and the file name as stored (plan 4.4
 * "Rules", erratum of breaker round 1 of phase B). Everything that must agree on what "the same store" is derives from
 * ONE of these per locked round: the OS key item's identity ([StoreIdentity]), the header's fingerprint, the key cache
 * entry, and every read, write, quarantine move and create of that round (so the stored spelling never flips on a
 * volume that keeps the spelling of a replaced name).
 *
 * No case folding and no fallback: a volume that folds case gives one spelling for two names of one file (the stored
 * one), a volume that does not gives two stores; folding is for the in-process lock key only (over-locking is harmless,
 * a folded identity is a collision that deletes another store's key).
 */
internal class RealStorePath(
    /** The canonical path of the directory. */
    val directory: String,
    /** The file name as the volume stores it (the spelled name while the file does not exist yet). */
    val name: String,
) {
    /** The store file. */
    val file: File get() = File(directory, name)

    /** The whole path, for the lock key. */
    val path: String get() = file.path

    override fun toString(): String = "RealStorePath($path)"
}

/**
 * Resolves the real path of a store file. A failure is an [IOException] or a [SecurityException] for that one call and
 * never a different path: a transient failure must not become a second identity for the life of an instance. Reads the
 * file system, so it runs on the store's I/O dispatcher.
 */
internal fun interface StorePathResolver {
    fun resolve(file: File): RealStorePath

    companion object {
        /** The resolver of production code. */
        val real: StorePathResolver = StorePathResolver { file: File -> resolveReal(file) }

        private fun isLinux(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)

        /**
         * `File.canonicalFile` resolves every existing component to its stored spelling on macOS (APFS, HFS+) and Windows and
         * keeps the spelling of what does not exist yet; the JDK cannot report the stored spelling on Linux, where a
         * case-insensitive directory (ext4 casefold, vfat) may hold the file under another spelling, so the directory is
         * listed there when the file exists.
         */
        private fun resolveReal(file: File): RealStorePath {
            val canonical: File = file.canonicalFile
            val parent: File = canonical.parentFile ?: return RealStorePath(canonical.path, "")
            val name: String = if (isLinux() && canonical.exists()) storedName(parent, canonical.name) else canonical.name
            return RealStorePath(parent.path, name)
        }

        /** The entry of [parent] that is [spelled] itself, or else the one that is the same file with another spelling. */
        private fun storedName(parent: File, spelled: String): String {
            val names: Array<String> = parent.list() ?: throw IOException("Could not list ${parent.path}")
            if (spelled in names) return spelled
            val spelledPath = File(parent, spelled).toPath()
            val folded: String = PathKey.of(spelled, CaseFolding.Always)
            return names.firstOrNull { candidate: String ->
                PathKey.of(candidate, CaseFolding.Always) == folded && Files.isSameFile(File(parent, candidate).toPath(), spelledPath)
            } ?: spelled
        }
    }
}
