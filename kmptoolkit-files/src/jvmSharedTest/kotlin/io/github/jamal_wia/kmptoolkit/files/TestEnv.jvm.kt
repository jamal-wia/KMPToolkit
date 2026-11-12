@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.nio.file.Files
import org.junit.Assume

internal actual object TestEnv {
    actual fun newRoot(): String = Files.createTempDirectory("kmptoolkit-files").toFile().path

    actual fun deleteTree(path: String) {
        File(path).deleteRecursively()
    }

    actual fun realOps(): FileSystemOps = NioFileSystemOps()

    actual fun createSymbolicLink(link: String, target: String): Boolean =
        try {
            Files.createSymbolicLink(File(link).toPath(), File(target).toPath())
            true
        } catch (_: Exception) {
            false
        }

    actual fun setLastModified(path: String, millis: Long): Boolean = File(path).setLastModified(millis)

    actual fun makeUnwritable(directory: String): AutoCloseable? {
        // A super-user defeats the POSIX mechanism: detect that with a control folder restricted the same way.
        val control: File = Files.createTempDirectory("kmptoolkit-files-control").toFile()
        try {
            val restore: AutoCloseable = TestFs.makeUnwritable(control)
            val canStillWrite: Boolean = try {
                File(control, "probe").createNewFile()
            } catch (_: Exception) {
                false
            }
            restore.close()
            if (canStillWrite) return null
        } finally {
            control.deleteRecursively()
        }
        return TestFs.makeUnwritable(File(directory))
    }

    actual fun openDescriptorCount(): Long? = TestFs.openDescriptorCount()

    actual fun skip(reason: String): Nothing {
        Assume.assumeTrue(reason, false)
        error("unreachable: the assumption above skips the test")
    }
}
