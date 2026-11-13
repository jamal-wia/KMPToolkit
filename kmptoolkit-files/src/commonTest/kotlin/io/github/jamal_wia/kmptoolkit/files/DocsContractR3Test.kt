@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the sentences under test are about raw spellings (repeated separators, dot segments)

/**
 * Round 3 of the breaker for unit G, docs as an executable contract: sentences that round 2's fix added to
 * 04-api-reference.md ("Nothing else is normalized: `.`, `..` and repeated separators inside a path reach the platform
 * unchanged"; "a final `.` or `..` names a directory, never a link"; "`move("a/", "b/")` renames `a` to `b`, moving a
 * link as a link") and had no test of their own.
 */
class DocsContractR3Test {
    private lateinit var root: String
    private lateinit var ops: FaultOps
    private lateinit var engine: FileAccess
    private lateinit var plain: FileAccess

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        ops = FaultOps(TestEnv.realOps())
        engine = fileAccessOver(ops)
        plain = fileAccessOver(TestEnv.realOps())
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private val posix: Boolean get() = TestHost.pathSyntax == PathSyntax.POSIX

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun pathsOf(op: Op): List<String> = ops.log.filter { it.op == op }.map { it.path }

    @Test
    fun `dot segments and repeated separators inside a path reach the platform unchanged`() {
        if (!posix) return
        assertTrue(plain.writeAtomically(p("d/sub/f"), "x".encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
        val spelled: String = root + "//d/./sub/../sub//f"
        assertEquals("x", engine.readBytes(spelled, 10).getOrNull()?.decodeToString())
        assertEquals(listOf(spelled), pathsOf(Op.Metadata).distinct())
        assertEquals(listOf(spelled), pathsOf(Op.OpenForRead))
        ops.log.clear()
        // a trailing separator is the one thing dropped: the rest of the spelling stays
        assertEquals(FileKind.RegularFile, engine.metadata("$spelled/")?.kind)
        assertEquals(listOf(spelled), pathsOf(Op.Metadata))
        ops.log.clear()
        assertTrue(engine.writeAtomically(root + "//d/./w", "y".encodeToByteArray()).isSuccess)
        val temps: List<String> = pathsOf(Op.CreateNew)
        assertEquals(1, temps.size)
        assertTrue(temps.single().startsWith(root + "//d/./"), "the temp file is named next to the spelled path: ${temps.single()}")
    }

    @Test
    fun `a final dot names the directory behind a link and a trailing separator on a link to move keeps the link a link`() {
        if (!posix) return
        assertTrue(plain.createDirectories(p("d")).isSuccess)
        assertTrue(plain.writeAtomically(p("d/in"), "keep".encodeToByteArray()).isSuccess)
        if (!TestEnv.createSymbolicLink(p("l"), p("d"))) return
        assertEquals(FileKind.Directory, plain.metadata(p("l") + "/.", followLinks = false)?.kind)
        assertEquals(FileKind.SymbolicLink, plain.metadata(p("l") + "/", followLinks = false)?.kind)
        // asking to delete "l/." must never remove the link itself or the directory it names
        plain.delete(p("l") + "/.")
        assertEquals(FileKind.SymbolicLink, plain.metadata(p("l"), followLinks = false)?.kind)
        assertEquals("keep", plain.readBytes(p("d/in"), 10).getOrNull()?.decodeToString())
        // move("l/", "m/") renames the link, not its target
        assertTrue(plain.move(p("l") + "/", p("m") + "/").isSuccess)
        assertEquals(null, plain.metadata(p("l"), followLinks = false))
        assertEquals(FileKind.SymbolicLink, plain.metadata(p("m"), followLinks = false)?.kind)
        assertEquals(FileKind.Directory, plain.metadata(p("d"), followLinks = false)?.kind)
        assertEquals("keep", plain.readBytes(p("m/in"), 10).getOrNull()?.decodeToString())
    }
}
