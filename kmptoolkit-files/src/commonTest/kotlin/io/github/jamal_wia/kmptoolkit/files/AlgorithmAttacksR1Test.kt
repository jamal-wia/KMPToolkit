@file:OptIn(ToolkitInternalApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Round 1 of the breaker for unit G: the algorithms of the engine on a real file system under boundary and hostile input. */
class AlgorithmAttacksR1Test {
    private lateinit var root: String
    private lateinit var fs: FileAccess

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        fs = fileAccessOver(TestEnv.realOps())
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun put(relative: String, content: ByteArray): String {
        val path: String = p(relative)
        val result: FileResult<Unit> = fs.writeAtomically(path, content, WriteOptions(durability = Durability.Buffered, createParentDirectories = true))
        assertTrue(result.isSuccess, "setup write of '$relative' failed: $result")
        return path
    }

    private fun realTime(seconds: Int = 120, body: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = seconds.seconds) {
            withContext(Dispatchers.Default) { withTimeout(seconds * 1000L - 5000L) { body() } }
        }

    private fun allNames(directory: String): List<String> {
        val out: MutableList<String> = ArrayList()
        fun walk(dir: String, prefix: String) {
            for (name: String in fs.list(dir).getOrNull().orEmpty()) {
                val child: String = TestPaths.join(dir, name)
                out += prefix + name
                if (fs.metadata(child, false)?.kind == FileKind.Directory) walk(child, "$prefix$name/")
            }
        }
        walk(directory, "")
        return out.sorted()
    }

    @Test
    fun `readBytes at every size and maxBytes boundary returns the whole file or TooLarge with the exact size`() {
        val sizes: List<Int> = listOf(0, 1, 2, 3, 4095, 4096, 4097, 8191, 8192, 8193, 65535, 65536, 65537, 131072)
        for (size: Int in sizes) {
            val content: ByteArray = ByteArray(size) { (it * 31 + size).toByte() }
            val path: String = put("f$size", content)
            for (max: Long in listOf(size - 1L, size.toLong(), size + 1L, 0L, FileAccess.MAX_READ_BYTES)) {
                if (max < 0) continue
                val result: FileResult<ByteArray> = fs.readBytes(path, max)
                if (size <= max) {
                    assertContentEquals(content, result.getOrNull(), "size $size max $max")
                } else {
                    val error: FileError? = result.errorOrNull()
                    assertTrue(error is FileError.TooLarge && error.sizeBytes == size.toLong() && error.maxBytes == max, "size $size max $max: $result")
                }
            }
        }
    }

    @Test
    fun `plain but hostile names survive write list read move sweep tree size and delete`() {
        val hostile: List<String> = listOf(
            "a b", "-rf", "new\nline", "tab\there", "\\back\\slash", "..hidden", "...", "~", "\$HOME", "*", "?", "[x]", "{a,b}", "'quote'", "%s%n",
            "café", "café", "日本語", "😀", "‮txt.exe", "\u0000x".drop(1), "a".repeat(255), "€".repeat(85),
            "ﬁle", "ı", "I", "İ", "ss", "ß", ".leading", "trailing~", "name with  double  spaces",
        ).filter { FileNames.isPlainName(it) }
        assertTrue(hostile.size >= 20, "too few usable names: ${hostile.size}")
        for (name: String in hostile) {
            val dir: String = p("case${hostile.indexOf(name)}")
            assertTrue(fs.createDirectories(dir).isSuccess)
            val file: String = TestPaths.join(dir, name)
            assertTrue(fs.writeAtomically(file, "v1".encodeToByteArray()).isSuccess, "write '$name'")
            assertTrue(fs.writeAtomically(file, "v2".encodeToByteArray()).isSuccess, "rewrite '$name'")
            assertEquals(listOf(name), fs.list(dir).getOrNull(), "list shows exactly the created spelling of '$name'")
            assertEquals("v2", fs.readBytes(file, 10).getOrNull()?.decodeToString(), "read '$name'")
            assertEquals(2L, fs.treeSize(dir).getOrNull(), "treeSize '$name'")
            val moved: String = TestPaths.join(dir, "moved-$name".take(200))
            assertTrue(fs.move(file, moved).isSuccess, "move '$name'")
            assertNull(fs.metadata(file), "the source of '$name' is gone")
            assertTrue(fs.sweep(dir, 0, CancellationCheck.Never) { true }.isSuccess, "sweep ran")
            assertTrue(fs.deleteRecursively(dir).isSuccess, "deleteRecursively '$name'")
            assertNull(fs.metadata(dir))
        }
        assertEquals(emptyList(), allNames(root), "nothing is left behind")
    }

    @Test
    fun `sixteen racing no-replace writers of one path have exactly one winner and no leftovers`() = realTime {
        for (round: Int in 0 until 40) {
            val target: String = p("race$round")
            val outcomes: List<FileResult<Unit>> = (0 until 16).map { writer: Int ->
                async { fs.writeAtomically(target, "writer-$writer".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail, durability = Durability.Buffered)) }
            }.awaitAll()
            val winners: List<Int> = outcomes.indices.filter { outcomes[it].isSuccess }
            assertEquals(1, winners.size, "round $round: $outcomes")
            for (index: Int in outcomes.indices) {
                if (index != winners.single()) assertTrue(outcomes[index].errorOrNull() is FileError.AlreadyExists, "round $round writer $index: ${outcomes[index]}")
            }
            assertEquals("writer-${winners.single()}", fs.readBytes(target, 100).getOrNull()?.decodeToString())
        }
        val leftovers: List<String> = fs.list(root).getOrNull().orEmpty().filter { FileNames.isTempName(it) }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun `deleteRecursively cancelled at every poll leaves a subset and never touches a link target and a rerun finishes`() {
        for (stopAt: Int in 0..40) {
            val tree: String = p("t$stopAt")
            put("t$stopAt/a/x", "x".encodeToByteArray())
            put("t$stopAt/a/b/y", "y".encodeToByteArray())
            put("t$stopAt/a/b/c/z", "z".encodeToByteArray())
            put("t$stopAt/w", "w".encodeToByteArray())
            put("outside$stopAt/keep", "keep".encodeToByteArray())
            fs.createDirectories(TestPaths.join(tree, "empty"))
            if (TestEnv.createSymbolicLink(TestPaths.join(tree, "a/link"), p("outside$stopAt"))) {
                TestEnv.createSymbolicLink(TestPaths.join(tree, "loop"), tree)
            }
            val before: List<String> = allNames(tree)
            var polls: Int = 0
            val cancel: CancellationCheck = CancellationCheck { ++polls > stopAt }
            val result: FileResult<Unit> = fs.deleteRecursively(tree, cancel)
            if (result.isSuccess) {
                assertNull(fs.metadata(tree), "stopAt $stopAt: success means nothing is left")
            } else {
                assertEquals(FileError.Cancelled, result.errorOrNull(), "stopAt $stopAt")
                val after: List<String> = allNames(tree)
                assertTrue(before.containsAll(after), "stopAt $stopAt: the cancelled walk created something: $after")
            }
            assertEquals("keep", fs.readBytes(p("outside$stopAt/keep"), 10).getOrNull()?.decodeToString(), "stopAt $stopAt: the link target was touched")
            assertTrue(fs.deleteRecursively(tree).isSuccess, "stopAt $stopAt: the rerun")
            assertNull(fs.metadata(tree))
        }
    }

    @Test
    fun `treeSize cancelled at every poll is Cancelled or the full size and never a partial sum`() {
        put("t/a/x", ByteArray(10))
        put("t/a/b/y", ByteArray(20))
        put("t/w", ByteArray(5))
        val full: Long = assertNotNull(fs.treeSize(p("t")).getOrNull())
        assertEquals(35L, full)
        for (stopAt: Int in 0..12) {
            var polls: Int = 0
            val result: FileResult<Long> = fs.treeSize(p("t")) { ++polls > stopAt }
            val value: Long? = result.getOrNull()
            if (value != null) assertEquals(full, value, "stopAt $stopAt") else assertEquals(FileError.Cancelled, result.errorOrNull(), "stopAt $stopAt")
        }
    }

    @Test
    fun `a one character temp prefix works and its leftovers are told apart by name`() {
        for (prefix: String in listOf(".", "..", "-", " ", "x", "é", "a b.", "~")) {
            val config: FileAccessConfig = FileAccessConfig(prefix)
            val access: FileAccess = fileAccessOver(TestEnv.realOps(), config)
            val directory: String = p("pfx-" + prefix.hashCode())
            assertTrue(access.createDirectories(directory).isSuccess)
            val target: String = TestPaths.join(directory, "target")
            assertTrue(access.writeAtomically(target, "a".encodeToByteArray()).isSuccess, "prefix '$prefix'")
            assertEquals(listOf("target"), access.list(directory).getOrNull(), "prefix '$prefix' left a temp file")
            assertTrue(access.writeAtomically(target, "b".encodeToByteArray(), WriteOptions(tempPrefix = "zz-")).isSuccess)
            assertTrue(FileNames.isTempName("${prefix}123.tmp", prefix), "prefix '$prefix'")
            assertFalse(FileNames.isTempName("target", prefix))
        }
    }

    @Test
    fun `a target named like a temp file is written and replaced like any other`() {
        val named: String = p("d/" + FileAccessConfig.DEFAULT_TEMP_PREFIX + "123.tmp")
        assertTrue(fs.writeAtomically(named, "one".encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
        assertTrue(fs.writeAtomically(named, "two".encodeToByteArray()).isSuccess)
        assertEquals("two", fs.readBytes(named, 10).getOrNull()?.decodeToString())
        assertEquals(1, fs.list(p("d")).getOrNull()?.size)
    }

    @Test
    fun `sweep keeps future dated entries whatever the age and deletes old ones at the maximum age boundary`() {
        val old: String = put("s/old", "o".encodeToByteArray())
        val future: String = put("s/future", "f".encodeToByteArray())
        val fresh: String = put("s/fresh", "n".encodeToByteArray())
        if (!TestEnv.setLastModified(old, 1_000_000L) || !TestEnv.setLastModified(future, 253_402_300_799_000L)) TestEnv.skip("the host cannot date files")
        assertEquals(0, fs.sweep(p("s"), Long.MAX_VALUE - 1) { it == "old" }.getOrNull(), "an age near Long.MAX_VALUE keeps an entry of 1970")
        assertNotNull(fs.metadata(old), "kept")
        assertEquals(1, fs.sweep(p("s"), 1_000_000L) { true }.getOrNull())
        assertNull(fs.metadata(old))
        assertNotNull(fs.metadata(future), "dated in the future: kept")
        assertNotNull(fs.metadata(fresh), "younger than the age: kept")
        assertEquals(0, fs.sweep(p("s"), 0) { it == "future" }.getOrNull())
    }

    @Test
    fun `deleteRecursively of a directory holding a link to its own parent removes only the link`() {
        put("a/b/f", "f".encodeToByteArray())
        if (!TestEnv.createSymbolicLink(p("a/b/up"), p("a"))) TestEnv.skip("the host cannot create symbolic links")
        assertTrue(fs.deleteRecursively(p("a/b")).isSuccess)
        assertNull(fs.metadata(p("a/b")))
        assertNotNull(fs.metadata(p("a")))
    }
}
