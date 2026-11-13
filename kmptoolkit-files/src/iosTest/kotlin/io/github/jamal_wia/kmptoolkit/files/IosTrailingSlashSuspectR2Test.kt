package io.github.jamal_wia.kmptoolkit.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G, step 4 (iOS differential): unlike the JDK, iOS hands a trailing separator to the
 * kernel, where `lstat("link/")` follows the link. 04 says `deleteRecursively` removes the path "without following any
 * symbolic link", `delete` never removes a link target and `treeSize` counts a link as 0. A red test is a SUSPECT and is
 * not committed.
 */
class IosTrailingSlashSuspectR2Test {
    private val files: FileAccess = createFileAccess()

    private fun withLinkToDirectory(block: (root: String, link: String, targetFile: String) -> Unit) {
        val root: String = TestEnv.newRoot()
        try {
            val directory: String = root + "/dir"
            assertTrue(files.createDirectories(directory).isSuccess)
            val inner: String = "$directory/in"
            assertTrue(files.writeAtomically(inner, "keep me".encodeToByteArray()).isSuccess)
            val link: String = root + "/link"
            assertTrue(TestEnv.createSymbolicLink(link, directory))
            block(root, link, inner)
        } finally {
            TestEnv.deleteTree(root)
        }
    }

    @Test
    fun `deleteRecursively of a link with a trailing slash never touches the target`() =
        withLinkToDirectory { _: String, link: String, inner: String ->
            files.deleteRecursively("$link/")
            assertEquals("keep me", files.readBytes(inner, 100).getOrNull()?.decodeToString())
        }

    @Test
    fun `delete of a link with a trailing slash never touches the target`() =
        withLinkToDirectory { _: String, link: String, inner: String ->
            files.delete("$link/")
            assertEquals("keep me", files.readBytes(inner, 100).getOrNull()?.decodeToString())
        }

    @Test
    fun `treeSize of a link with a trailing slash counts the link as zero`() =
        withLinkToDirectory { _: String, link: String, _: String ->
            assertEquals(FileResult.Success(0L), files.treeSize("$link/"))
        }

    @Test
    fun `delete of a file with a trailing slash is not a Success that leaves the file`() =
        withLinkToDirectory { _: String, _: String, inner: String ->
            val result: FileResult<Unit> = files.delete("$inner/")
            val stillThere: Boolean = files.metadata(inner, false) != null
            assertTrue(!(result is FileResult.Success && stillThere), "delete answered $result and the file is still there")
        }
}
