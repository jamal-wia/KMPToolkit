package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * The creation-time attributes that make a new file or directory accessible to its owner only, chosen by what the
 * file system of the path supports:
 *
 * - POSIX permissions: mode 0600 for a file, 0700 for a directory;
 * - Windows ACL: a DACL of two allow entries, the current user and SYSTEM, with no entry of the parent; the entries of
 *   a directory are inheritable by the files and directories below it;
 * - neither (FAT, exFAT on a host that exposes only the basic view): no attribute, which is the documented "ignored".
 *
 * The attributes are passed to the call that creates the entry, so the entry never exists with wider access; nothing
 * here changes an entry after creation. When [enabled] is `false` (Android, whose app sandbox already hides the
 * files) every function answers no attribute.
 */
@OptIn(ToolkitInternalApi::class)
internal open class OwnerOnlyAttributes(private val enabled: Boolean) {

    /** The attributes for a new regular file at [path]; empty when nothing applies. */
    open fun forFile(path: Path): Array<FileAttribute<*>> = attributes(path, directory = false)

    /** The attributes for a new directory at [path]; empty when nothing applies. */
    open fun forDirectory(path: Path): Array<FileAttribute<*>> = attributes(path, directory = true)

    /** The attribute views the file system of [path] supports; a seam for tests. */
    internal open fun supportedViews(path: Path): Set<String> = path.fileSystem.supportedFileAttributeViews()

    private fun attributes(path: Path, directory: Boolean): Array<FileAttribute<*>> {
        if (!enabled) return NO_ATTRIBUTES
        val views: Set<String> = supportedViews(path)
        return when {
            // path-hygiene-ignore: PH2 the POSIX permission attribute is requested only when the file system reports the posix view
            "posix" in views -> arrayOf(PosixFilePermissions.asFileAttribute(posixPermissions(directory)))
            "acl" in views -> arrayOf(windowsAcl(path, directory))
            else -> NO_ATTRIBUTES
        }
    }

    // path-hygiene-ignore: PH2 the POSIX permission attribute is requested only when the file system reports the posix view
    private fun posixPermissions(directory: Boolean): Set<PosixFilePermission> =
        if (directory) {
            // path-hygiene-ignore: PH2 the POSIX permission attribute is requested only when the file system reports the posix view
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            // path-hygiene-ignore: PH2 the POSIX permission attribute is requested only when the file system reports the posix view
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }

    private fun windowsAcl(path: Path, directory: Boolean): FileAttribute<List<AclEntry>> {
        val principals: List<UserPrincipal> = try {
            windowsPrincipals(path.fileSystem)
        } catch (e: Exception) {
            // Fails closed: the caller asked for owner-only and it cannot be established, so nothing is created.
            throw FileOpException(FileError.Io(path.toString(), null, e))
        }
        val flags: Set<AclEntryFlag> = if (directory) {
            setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        } else {
            emptySet()
        }
        val entries: List<AclEntry> = principals.map { principal: UserPrincipal ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(*AclEntryPermission.values())
                .setFlags(*flags.toTypedArray())
                .build()
        }
        return object : FileAttribute<List<AclEntry>> {
            override fun name(): String = "acl:acl"

            override fun value(): List<AclEntry> = entries
        }
    }

    internal companion object {
        private val NO_ATTRIBUTES: Array<FileAttribute<*>> = emptyArray()

        /** The well-known account of the SYSTEM identity. */
        private const val SYSTEM_ACCOUNT: String = "NT AUTHORITY\\SYSTEM"

        /**
         * The current user and SYSTEM of the default file system, resolved once on first use (`lazy` as for
         * `nioAvailable` in FileAccessFactory.android.kt). The initializer only calls the account lookup of the
         * operating system, never foreign code, and a failed lookup throws out of it and is retried by the next call.
         */
        private val defaultPrincipals: List<UserPrincipal> by lazy { resolvePrincipals(FileSystems.getDefault()) }

        private fun windowsPrincipals(fileSystem: FileSystem): List<UserPrincipal> =
            if (fileSystem === FileSystems.getDefault()) defaultPrincipals else resolvePrincipals(fileSystem)

        private fun resolvePrincipals(fileSystem: FileSystem): List<UserPrincipal> {
            val lookup: UserPrincipalLookupService = fileSystem.userPrincipalLookupService
            val user: UserPrincipal = lookup.lookupPrincipalByName(System.getProperty("user.name"))
            val system: UserPrincipal = lookup.lookupPrincipalByName(SYSTEM_ACCOUNT)
            return listOf(user, system)
        }
    }
}
