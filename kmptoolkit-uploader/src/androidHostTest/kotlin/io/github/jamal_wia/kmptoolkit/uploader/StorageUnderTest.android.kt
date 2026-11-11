package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files

internal actual fun newStorageUnderTest(): StorageUnderTest {
    val root: File = Files.createTempDirectory("r6-diff").toFile()
    val probe: File = File(root, "probe-case").apply { writeBytes(byteArrayOf(1)) }
    val caseSensitive: Boolean = !File(root, probe.name.uppercase()).exists()
    probe.delete()
    return StorageUnderTest(AndroidOutcomeStorage(File(root, "outcomes"), File(root, "partial")), keepsCaseApart = caseSensitive) { root.deleteRecursively() }
}
