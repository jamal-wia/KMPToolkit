@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.files

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSNumber
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Breaker round 2 of group files-owner-only (STO-1, storage PLAN 3.7): on iOS `ownerOnly` is accepted and ignored (the
 * app sandbox already hides the files, WriteOptions.ownerOnly KDoc). The contract under attack: no ownerOnly shape is an
 * error on iOS (replace, no-replace, created parents, a created temp directory, durable directories, a cancellation-free
 * repeat), the content and the "no temp left" guarantees still hold, and the mode is exactly the one a plain call gives.
 */
class IosOwnerOnlyBreakerR2Test {
    private lateinit var root: String

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(vararg segments: String): String = (listOf(root) + segments).joinToString("/")

    private fun permissions(path: String): Long {
        val attributes: Map<Any?, *> = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: error("no attributes for $path")
        return (attributes[NSFilePosixPermissions] as NSNumber).longValue
    }

    private fun list(path: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null).orEmpty().map { it as String }.sorted()

    @Test
    fun `every ownerOnly shape succeeds on iOS and leaves the plain mode and no temp`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("plain"), byteArrayOf(1)).isSuccess)
        assertTrue(files.createDirectories(p("plainDir")).isSuccess)
        val plainFile: Long = permissions(p("plain"))
        val plainDir: Long = permissions(p("plainDir"))

        val options = WriteOptions(ownerOnly = true)
        repeat(3) { step: Int ->
            val written: FileResult<Unit> = files.writeAtomically(p("secret"), byteArrayOf(step.toByte()), options)
            assertTrue(written.isSuccess, "step $step: $written")
        }
        val refused: FileResult<Unit> = files.writeAtomically(p("secret"), byteArrayOf(9), options.copy(existing = ExistingFile.Fail))
        assertTrue(refused is FileResult.Failure && refused.error is FileError.AlreadyExists, "$refused")
        val nested: FileResult<Unit> = files.writeAtomically(
            p("a", "b", "f"),
            byteArrayOf(5),
            options.copy(durability = Durability.SyncedWithDirectory, createParentDirectories = true, tempDirectory = p("t", "u")),
        )
        assertTrue(nested.isSuccess, "$nested")
        assertTrue(files.createDirectories(p("d", "e"), durable = true, ownerOnly = true).isSuccess)
        assertTrue(files.createDirectories(p("d", "e"), durable = true, ownerOnly = true).isSuccess, "idempotent")

        assertEquals(listOf(2.toByte()), files.readBytes(p("secret"), 16).getOrNull()?.toList())
        assertEquals(plainFile, permissions(p("secret")), "ignored: the plain file mode")
        assertEquals(plainFile, permissions(p("a", "b", "f")), "ignored: the plain file mode")
        for (dir: String in listOf(p("a"), p("a", "b"), p("t"), p("t", "u"), p("d"), p("d", "e"))) {
            assertEquals(plainDir, permissions(dir), "ignored: the plain directory mode of $dir")
        }
        assertEquals(listOf<String>(), list(p("t", "u")), "no temp file is left")
        assertEquals(listOf("a", "d", "plain", "plainDir", "secret", "t"), list(root), "no temp file is left")
    }
}
