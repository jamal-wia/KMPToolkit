package io.github.jamal_wia.kmptoolkit.testsupport

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal

/**
 * `java.io.File` based test helpers that behave sensibly on both POSIX and Windows hosts: permission
 * changes that work where the file system supports them and skip the test (never silently pass it)
 * where it does not.
 *
 * Available to Android host tests and JVM tests, not to common or iOS tests.
 *
 * @since 5.0.0
 */
public object TestFs {

    /**
     * Makes [dir] refuse the creation of files in it, and returns the restorer (call it in `finally`
     * or `@AfterTest`; closing twice is harmless).
     *
     * POSIX: removes the write bits, which also blocks sub-directories and deleting entries.
     * Windows: an ACL DENY entry (add file, add sub-directory, write data, append data, delete child)
     * for the current user. Measured on Windows 11: that blocks creating a file, but NOT creating a
     * sub-directory, and a file that already exists can still be deleted (the file grants DELETE
     * itself). Where neither mechanism is supported the running test is skipped. Running as a
     * super-user defeats the POSIX mechanism: detect that with a control folder you restrict
     * yourself (not with the helper under test, or a broken helper would turn into a silent skip), and skip
     * only then, as the tests of this module do. On Windows never skip for that reason: an ACL DENY also
     * binds administrators.
     */
    public fun makeUnwritable(dir: File): AutoCloseable {
        require(dir.isDirectory) { "Not a directory: $dir" }
        return restrict(
            target = dir,
            posixRemove = setOf(
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.OWNER_WRITE,
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.GROUP_WRITE,
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.OTHERS_WRITE,
            ),
            aclDeny = setOf(
                AclEntryPermission.ADD_FILE,
                AclEntryPermission.ADD_SUBDIRECTORY,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.DELETE_CHILD,
            ),
        )
    }

    /**
     * Makes [file] (or a directory's listing) unreadable and returns the restorer; see [makeUnwritable]
     * for the mechanism and the limits.
     */
    public fun makeUnreadable(file: File): AutoCloseable {
        require(file.exists()) { "No such file: $file" }
        return restrict(
            target = file,
            posixRemove = setOf(
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.OWNER_READ,
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.GROUP_READ,
                // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
                PosixFilePermission.OTHERS_READ,
            ),
            aclDeny = setOf(AclEntryPermission.READ_DATA),
        )
    }

    /**
     * The number of file descriptors this process has open, or `null` when the host cannot say (Windows,
     * or a JVM without the Unix management bean and without `/proc/self/fd` or `/dev/fd`). A test that
     * needs the count calls `assumeTrue(count != null)`.
     */
    public fun openDescriptorCount(): Long? {
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) return null
        fromManagementBean()?.let { return it }
        // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
        for (directory: String in listOf("/proc/self/fd", "/dev/fd")) {
            File(directory).list()?.let { return it.size.toLong() }
        }
        return null
    }

    /**
     * [openDescriptorCount] for a test that needs the number: skips the running test on a host that is
     * known not to count descriptors (Windows) and FAILS on any other host, where a count that cannot
     * be read is a broken environment, not a reason to skip a leak test silently.
     *
     * @since 5.0.0
     */
    public fun requireOpenDescriptorCount(): Long {
        val count: Long? = openDescriptorCount()
        if (count != null) return count
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) {
            hostAssume(false, "this host cannot count open file descriptors")
        }
        throw AssertionError("the open descriptor count could not be read on a POSIX host (no management bean, no /proc/self/fd, no /dev/fd)")
    }

    private fun restrict(
        target: File,
        // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
        posixRemove: Set<PosixFilePermission>,
        aclDeny: Set<AclEntryPermission>,
    ): AutoCloseable {
        val path: Path = target.toPath()

        val posix: PosixFileAttributeView? = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
            val original: Set<PosixFilePermission> = posix.readAttributes().permissions()
            posix.setPermissions(original - posixRemove)
            return AutoCloseable { posix.setPermissions(original) }
        }

        val acl: AclFileAttributeView? = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        if (acl != null) {
            val user: String? = System.getProperty("user.name")
            val principal: UserPrincipal? = runCatching { path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(user) }
                .getOrNull()
            if (principal != null) {
                val original: List<AclEntry> = acl.acl
                val deny: AclEntry = AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(principal)
                    .setPermissions(aclDeny)
                    .build()
                acl.acl = listOf(deny) + original
                return AutoCloseable { acl.acl = original }
            }
        }

        hostAssume(false, "the file system supports neither POSIX permissions nor ACLs for $target")
        error("unreachable: the assumption above skips the test")
    }

    private fun fromManagementBean(): Long? = try {
        val bean: Any? = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getOperatingSystemMXBean")
            .invoke(null)
        // path-hygiene-ignore: PH2 TestFs is the one place that wraps the POSIX file API for tests, and it falls back to the Windows ACL where that differs
        val unix: Class<*> = Class.forName("com.sun.management.UnixOperatingSystemMXBean")
        if (unix.isInstance(bean)) unix.getMethod("getOpenFileDescriptorCount").invoke(bean) as Long else null
    } catch (_: Throwable) {
        null
    }
}
