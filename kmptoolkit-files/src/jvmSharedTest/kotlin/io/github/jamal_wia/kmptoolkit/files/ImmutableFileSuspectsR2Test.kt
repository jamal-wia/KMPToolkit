package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G (platform differential): a file with the macOS user-immutable flag (`chflags uchg`)
 * answers EPERM to delete and to a rename over it. 04-api-reference.md says `AccessDenied` covers `EACCES`, `EPERM`
 * and `EROFS`, and iOS maps EPERM that way; the JDK reports EPERM as an untyped "Operation not permitted". A red test
 * here is a SUSPECT and is not committed. Skipped where `chflags` does not exist.
 */
class ImmutableFileSuspectsR2Test {

    private fun chflags(flag: String, path: String): Boolean =
        try {
            val process: Process = ProcessBuilder("chflags", flag, path).redirectErrorStream(true).start()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }

    private fun withImmutable(block: (root: File, locked: String) -> Unit) {
        val root: File = Files.createTempDirectory("kmptoolkit-uchg-r2").toFile()
        val locked: File = File(root, "locked")
        try {
            locked.writeText("original")
            if (!chflags("uchg", locked.path)) return // not a BSD host
            try {
                block(root, locked.path)
            } finally {
                chflags("nouchg", locked.path)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun isAccessDenied(result: FileResult<*>): Boolean = result is FileResult.Failure && result.error is FileError.AccessDenied

    @Test
    fun `delete and writeAtomically and move onto an immutable file are AccessDenied and change nothing`() =
        withImmutable { root: File, locked: String ->
            val files: FileAccess = createFileAccess()
            val deleted: FileResult<Unit> = files.delete(locked)
            assertTrue(isAccessDenied(deleted), "delete: $deleted")
            val written: FileResult<Unit> = files.writeAtomically(locked, "new".encodeToByteArray())
            assertTrue(isAccessDenied(written), "writeAtomically: $written")
            val source: String = File(root, "source").path
            File(source).writeText("src")
            val moved: FileResult<Unit> = files.move(source, locked)
            assertTrue(isAccessDenied(moved), "move: $moved")
            assertEquals("original", File(locked).readText())
            assertEquals("src", File(source).readText())
            assertEquals(listOf("locked", "source"), files.list(root.path).getOrNull())
        }

    @Test
    fun `deleteRecursively over a tree with an immutable file reports AccessDenied and removes the rest`() =
        withImmutable { root: File, locked: String ->
            val files: FileAccess = createFileAccess()
            File(root, "other").writeText("o")
            val result: FileResult<Unit> = files.deleteRecursively(root.path)
            assertTrue(isAccessDenied(result), "deleteRecursively: $result")
            assertEquals(false, File(root, "other").exists())
            assertEquals(true, File(locked).exists())
        }
}
