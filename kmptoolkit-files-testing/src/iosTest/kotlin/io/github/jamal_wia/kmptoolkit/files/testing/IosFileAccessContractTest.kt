@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import kotlin.test.Test
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.symlink

/** The published contract against the real iOS implementation, on the simulator. */
class IosFileAccessContractTest {
    private fun fixture(): FileAccessFixture {
        val root: String = NSTemporaryDirectory().trimEnd('/') + "/kmptoolkit-files-contract-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)) { "no test root" }
        return FileAccessFixture(
            files = createFileAccess(),
            root = root,
            createSymbolicLink = { path: String, target: String -> check(symlink(target, path) == 0) { "symlink failed" } },
            cleanup = { NSFileManager.defaultManager.removeItemAtPath(root, null) },
        )
    }

    @Test
    fun `the real iOS FileAccess keeps the whole contract`() {
        FileAccessContract { fixture() }.verifyAll()
    }
}
