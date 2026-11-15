package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService

/**
 * The creation-time attributes that make the lock file owner-only when [CrossProcessLock] has to create it itself (a volume
 * without hard links, where the files layer's atomic create-if-absent is not available). They are chosen by the attribute
 * views the file system provider of the path reports, the same rule the files layer applies to its `ownerOnly` writes:
 *
 * - POSIX permissions: mode 0600;
 * - Windows ACL: a DACL of two allow entries, the current user (qualified `DOMAIN\name`, from the process token, never the
 *   `user.name` property) and SYSTEM, and no entry inherited from the parent, because an explicit DACL given at creation
 *   replaces the inherited one;
 * - neither (a provider that reports no posix and no acl view): no attribute.
 *
 * The attributes go to the call that creates the file, so the file never exists with wider access, and a failure to
 * establish them is thrown as an [IOException] (the caller answers `Failed`, nothing is created): there is no retry
 * without the attribute, which could only create the file with wider access. The files layer's own class does the same for
 * every other owner-only file but is not visible outside its module, hence this small copy; both state the same rules.
 *
 * Each function is a seam for tests: [supportedViews] (a machine without Windows cannot report an `acl` view),
 * [tokenUserName] and [lookup] (the account lookup of the operating system).
 */
internal class LockFileAttributes(
    private val supportedViews: (Path) -> Set<String> = { path: Path -> path.fileSystem.supportedFileAttributeViews() },
    private val tokenUserName: () -> String = { ntTokenUserName() },
    private val lookup: (Path) -> UserPrincipalLookupService = { path: Path -> path.fileSystem.userPrincipalLookupService },
) {

    /** The attributes for a new lock file at [path]; empty when nothing applies. Throws [IOException] when the DACL cannot be built. */
    fun forNewFile(path: Path): Array<FileAttribute<*>> {
        val views: Set<String> = supportedViews(path)
        return when {
            // path-hygiene-ignore: PH2 owner-only mode of the lock file, requested only when the file system reports the posix view
            "posix" in views -> arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            "acl" in views -> arrayOf(ownerAndSystemAcl(path))
            else -> emptyArray()
        }
    }

    private fun ownerAndSystemAcl(path: Path): FileAttribute<List<AclEntry>> {
        val principals: List<UserPrincipal> = try {
            val service: UserPrincipalLookupService = lookup(path)
            listOf(service.lookupPrincipalByName(tokenUserName()), systemPrincipal(service))
        } catch (error: Exception) {
            throw IOException("The owner-only ACL of the lock file could not be built", error)
        } catch (error: LinkageError) {
            // A JDK without the NTSystem module (reached by reflection) or without its native library.
            throw IOException("The owner-only ACL of the lock file could not be built", error)
        }
        val entries: List<AclEntry> = principals.map { principal: UserPrincipal ->
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(*AclEntryPermission.values())
                .build()
        }
        return object : FileAttribute<List<AclEntry>> {
            override fun name(): String = "acl:acl"

            override fun value(): List<AclEntry> = entries
        }
    }

    /** SYSTEM by its authority-qualified name first; the authority name is localised on some Windows editions. */
    private fun systemPrincipal(service: UserPrincipalLookupService): UserPrincipal {
        var failure: Exception? = null
        for (account: String in SYSTEM_ACCOUNTS) {
            try {
                return service.lookupPrincipalByName(account)
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        throw failure ?: IllegalStateException("no SYSTEM account name is configured")
    }

    internal companion object {
        private val SYSTEM_ACCOUNTS: List<String> = listOf("NT AUTHORITY\\SYSTEM", "SYSTEM")

        private const val NT_SYSTEM_CLASS: String = "com.sun.security.auth.module.NTSystem"

        /**
         * `DOMAIN\name` of the user of the process token, from the JDK's `NTSystem` (module jdk.security.auth), reached by
         * reflection so that a JDK without the module fails closed here instead of at class loading. Throws when the module
         * or its native library is absent or when it cannot name the domain: an unqualified name could resolve to a
         * different, local account.
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
