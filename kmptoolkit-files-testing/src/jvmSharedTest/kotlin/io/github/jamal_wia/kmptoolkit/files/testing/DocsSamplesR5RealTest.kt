package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** Round 5, Q4: every sample of docs 01-03 run against the real engine of this host over a temporary directory. */
class DocsSamplesR5RealTest {
    @Test
    fun `every sample of the overview and the getting started and the guide runs against the real engine`() = runTest {
        val root: File = Files.createTempDirectory("kmptoolkit-docs-r5").toRealPath().toFile()
        try {
            val files: FileAccess = createFileAccess()
            runGuideSamples(files, root.path, { parent, child -> File(parent, child).path }) { path ->
                check(File(path).setLastModified(1_000L)) { "cannot date $path" }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
