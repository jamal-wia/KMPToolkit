package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 4 of the breaker for unit G (engine mutant M18: the no-replace precheck of `writeAtomically` removed survived
 * every earlier test). Contract: `ExistingFile.Fail` with something at the path is `AlreadyExists`. The answer must not
 * depend on whether a temporary file could be created: in a directory that refuses new entries the existing entry is
 * still reported as `AlreadyExists`, and nothing is created or changed.
 */
class NoReplaceOrderR4Test {
    private val files: FileAccess = createFileAccess()
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("kmptoolkit-noreplace-r4").toRealPath().toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a no replace write over an existing file is AlreadyExists even where no temporary file can be created`() {
        val existing: File = File(directory, "f").also { it.writeText("OLD") }
        val restore: AutoCloseable = TestEnv.makeUnwritable(directory.path) ?: TestEnv.skip("a super-user ignores directory permissions")
        try {
            val failed: FileResult<Unit> = files.writeAtomically(existing.path, "NEW".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))
            assertEquals(FileError.AlreadyExists(existing.path), failed.errorOrNull(), "$failed")
            // The control: a replacing write in the same directory cannot make its temporary file.
            assertTrue(files.writeAtomically(existing.path, "NEW".encodeToByteArray()).errorOrNull() is FileError.AccessDenied)
        } finally {
            restore.close()
        }
        assertEquals("OLD", existing.readText())
        assertEquals(listOf("f"), directory.list()!!.sorted(), "an attempt left an entry behind")
    }
}
