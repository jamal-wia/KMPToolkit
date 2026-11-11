package io.github.jamal_wia.kmptoolkit.core

import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Runs on every host and pins what "absolute" means on that host: a `/`-rooted path is absolute
 * exactly where `/` is the root (macOS, Linux, iOS, an Android device), a drive or UNC path exactly on
 * Windows. The Windows rows therefore execute only on a Windows machine.
 */
@OptIn(ToolkitInternalApi::class)
class FilePathsTest {
    private val posixHost: Boolean = TestHost.pathSyntax == PathSyntax.POSIX
    private val windowsHost: Boolean = TestHost.pathSyntax == PathSyntax.WINDOWS

    @Test
    fun relativePathsAreNeverAbsolute() {
        for (path: String in listOf("", ".", "..", "a", "a/b", "a/b/", "./a", "../a", "~", "~/a", "a:b")) {
            assertFalse(isAbsoluteFilePath(path), "relative on every host: '$path'")
        }
    }

    @Test
    fun aSlashRootedPathIsAbsoluteWhereSlashIsTheRoot() {
        for (path: String in listOf("/", "/a", "/a/b", "/a/../b")) {
            assertEquals(posixHost, isAbsoluteFilePath(path), "'$path'")
        }
    }

    @Test
    fun aDriveOrShareRootedPathIsAbsoluteOnlyOnWindows() {
        for (path: String in listOf("C:\\", "C:\\data\\x", "c:/data/x", "\\\\server\\share\\x")) {
            assertEquals(windowsHost, isAbsoluteFilePath(path), "'$path'")
        }
    }

    @Test
    fun aDriveRelativeOrRootOnlyWindowsPathIsNotAbsolute() {
        for (path: String in listOf("C:data", "C:", "\\data")) {
            assertFalse(isAbsoluteFilePath(path), "'$path'")
        }
    }
}
