package io.github.jamal_wia.kmptoolkit.uploader

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
internal actual fun newStorageUnderTest(): StorageUnderTest {
    val root: String = NSTemporaryDirectory() + "/kmptoolkit-r6-diff-" + NSUUID().UUIDString()
    return StorageUnderTest(IosOutcomeStorage("$root/outcomes"), keepsCaseApart = true) { NSFileManager.defaultManager.removeItemAtPath(root, error = null) }
}
