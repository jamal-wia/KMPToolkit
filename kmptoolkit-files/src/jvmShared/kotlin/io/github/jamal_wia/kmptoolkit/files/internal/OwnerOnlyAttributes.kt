package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import java.nio.file.FileSystem
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
 * The creation-time attributes that make a new file or directory accessible to its owner only, chosen by the attribute
 * views the file system PROVIDER of the path reports (a property of the provider, not of the volume):
 *
 * - POSIX permissions: mode 0600 for a file, 0700 for a directory (on macOS the mode is the whole guarantee: an extended
 *   ACL entry that the parent marks inheritable is still inherited by the new entry, and the JDK cannot clear it);
 * - Windows ACL: a DACL of two allow entries, the current user and SYSTEM, with no entry of the parent; the entries of
 *   a directory are inheritable by the files and directories below it. The DACL is explicit and has no inherited entry
 *   at creation, but it does not carry the protected flag (the JDK cannot set it), so a later re-propagation from the
 *   parent can merge inherited entries into it;
 * - neither (a provider that reports no posix and no acl view, for example a zip file system): no attribute, which is
 *   the documented "ignored".
 *
 * A volume without permissions (FAT, exFAT) is not told apart here: its provider still reports the posix view
 * (Linux, macOS) or the acl view (Windows), the attribute is passed and the volume disregards it.
 *
 * The attributes are passed to the call that creates the entry, so the entry never exists with wider access; nothing
 * here changes an entry after creation, and a refusal of the attribute is never retried without it. When [enabled] is
 * `false` (Android, whose app sandbox already hides the files) every function answers no attribute.
 *
 * @param tokenUserName the account name of the user of the process token, qualified as `DOMAIN\name`; a seam for
 *   tests. The default asks the JDK's `NTSystem`. It must throw rather than answer an unqualified name.
 */
@OptIn(ToolkitInternalApi::class)
internal open class OwnerOnlyAttributes(
    private val enabled: Boolean,
    private val tokenUserName: () -> String = { ntTokenUserName() },
) {

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
        } catch (e: LinkageError) {
            // The same for a JDK without the NTSystem module (reached by reflection) or without its native library.
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

    /**
     * The current user and SYSTEM of one file system, resolved once and published as an immutable pair. Two threads
     * may both resolve on the first call; the result is the same and no lock is held across the account lookup of the
     * operating system. A failed lookup is not cached: the next call tries again.
     */
    @Volatile
    private var resolved: Pair<FileSystem, List<UserPrincipal>>? = null

    private fun windowsPrincipals(fileSystem: FileSystem): List<UserPrincipal> {
        val known: Pair<FileSystem, List<UserPrincipal>>? = resolved
        if (known != null && known.first === fileSystem) return known.second
        val principals: List<UserPrincipal> = resolvePrincipals(fileSystem.userPrincipalLookupService)
        resolved = fileSystem to principals
        return principals
    }

    /** The user and SYSTEM through [lookup]; internal as a seam for tests. */
    internal fun resolvePrincipals(lookup: UserPrincipalLookupService): List<UserPrincipal> {
        // Never user.name: it is a plain system property that any code in the process can change, and a bare name is
        // looked up among the local accounts before the domain ones.
        val user: UserPrincipal = lookup.lookupPrincipalByName(tokenUserName())
        return listOf(user, lookupSystem(lookup))
    }

    /** SYSTEM by its authority-qualified name first; the authority name is localised on some Windows editions. */
    internal fun lookupSystem(lookup: UserPrincipalLookupService): UserPrincipal {
        var failure: Exception? = null
        for (account: String in SYSTEM_ACCOUNTS) {
            try {
                return lookup.lookupPrincipalByName(account)
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        throw failure ?: IllegalStateException("no SYSTEM account name is configured")
    }

    internal companion object {
        private val NO_ATTRIBUTES: Array<FileAttribute<*>> = emptyArray()

        /** The names of the SYSTEM identity, in the order they are tried. */
        private val SYSTEM_ACCOUNTS: List<String> = listOf("NT AUTHORITY\\SYSTEM", "SYSTEM")

        private const val NT_SYSTEM_CLASS: String = "com.sun.security.auth.module.NTSystem"

        /**
         * `DOMAIN\name` of the user of the process token, from the JDK's `NTSystem` (module jdk.security.auth). Reached
         * by reflection because this source set also compiles for Android, whose class path has no such class. Throws
         * when the module or its native library is absent, or when it cannot name the domain: the caller then fails
         * closed, because an unqualified name could resolve to a different, local account.
         */
        internal fun ntTokenUserName(): String {
            val ntSystem: Any = Class.forName(NT_SYSTEM_CLASS).getDeclaredConstructor().newInstance()
            val name: String? = ntSystem.javaClass.getMethod("getName").invoke(ntSystem) as String?
            val domain: String? = ntSystem.javaClass.getMethod("getDomain").invoke(ntSystem) as String?
            check(!name.isNullOrEmpty() && !domain.isNullOrEmpty()) { "NTSystem reported no qualified user name" }
            return "$domain\\$name"
        }
    }
}
