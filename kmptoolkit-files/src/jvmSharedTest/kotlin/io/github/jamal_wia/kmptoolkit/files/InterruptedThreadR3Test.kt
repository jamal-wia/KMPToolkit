package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 3 of the breaker for unit G (lifecycle): a thread whose interrupt flag is set (a coroutine cancelled through
 * runInterruptible, an executor shutdown) calls the engine. java.nio file channels are interruptible, so the JDK closes
 * the channel and throws ClosedByInterruptException; 04-api-reference.md says no function throws and a failed call
 * leaves nothing behind, so every call must answer a typed result, remove its temporary file and release its handle.
 * The flag is always cleared before the assertions run.
 */
class InterruptedThreadR3Test {
    private val files: FileAccess = createFileAccess()

    @Test
    fun `calls on an interrupted thread never throw and leave no temp file and no open handle`() {
        val root: File = Files.createTempDirectory("kmptoolkit-intr-r3").toFile()
        try {
            val f: File = File(root, "f")
            f.writeText("old")
            File(root, "d").mkdir()
            File(root, "d/a").writeText("a")
            val before: Long? = TestFsDescriptors.count()
            val thrown: MutableList<String> = mutableListOf()
            val results: MutableMap<String, Any?> = linkedMapOf()
            Thread.currentThread().interrupt()
            try {
                fun record(name: String, call: () -> Any?) {
                    try {
                        results[name] = call()
                    } catch (t: Throwable) {
                        thrown += "$name threw $t"
                    }
                }
                record("readBytes") { files.readBytes(f.path, 100) }
                record("writeAtomically") { files.writeAtomically(f.path, "new".encodeToByteArray()) }
                record("writeAtomically Fail") { files.writeAtomically(File(root, "g").path, "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)) }
                record("move") { files.move(f.path, File(root, "m").path) }
                record("list") { files.list(File(root, "d").path) }
                record("treeSize") { files.treeSize(root.path) }
                record("deleteRecursively") { files.deleteRecursively(File(root, "d").path) }
            } finally {
                Thread.interrupted()
            }
            assertEquals(emptyList(), thrown)
            assertEquals(emptyList(), root.list().orEmpty().filter { FileNames.isTempName(it) }, "results: $results")
            val after: Long? = TestFsDescriptors.count()
            if (before != null && after != null) assertTrue(after - before <= 2, "descriptors went from $before to $after")
            // the old content is whole or the new content is whole, never lost
            val content: String? = (if (f.exists()) f else File(root, "m")).takeIf { it.exists() }?.readText()
            assertTrue(content == "old" || content == "new", "content: $content, results: $results")
            for ((name: String, result: Any?) in results) {
                if (result is FileResult.Failure) assertTrue(result.error !is FileError.Cancelled, "$name: $result")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

private object TestFsDescriptors {
    fun count(): Long? = io.github.jamal_wia.kmptoolkit.testsupport.TestFs.openDescriptorCount()
}
