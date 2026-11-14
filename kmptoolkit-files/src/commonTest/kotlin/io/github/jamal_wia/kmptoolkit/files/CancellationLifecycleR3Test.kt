@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 3 of the breaker for unit G: cancellation at every low-level step of every long operation, run through
 * [withIo] on a test dispatcher so the cancel happens exactly between two steps (no sleep, no race), and the lifecycle
 * of everything the caller hands in (a throwing lambda, a throwing check, a locked key). After each cancel: no
 * temporary file, no half-written destination, no open descriptor, no held key; the operation can simply be run again.
 */
class CancellationLifecycleR3Test {
    private lateinit var root: String
    private lateinit var plain: FileAccess
    private var descriptorsBefore: Long? = null

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        plain = fileAccessOver(TestEnv.realOps())
        descriptorsBefore = TestEnv.openDescriptorCount()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
        val before: Long? = descriptorsBefore
        val after: Long? = TestEnv.openDescriptorCount()
        if (before != null && after != null) assertTrue(after - before <= 3, "descriptors went from $before to $after")
    }

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun put(relative: String, bytes: ByteArray): String {
        val path: String = p(relative)
        assertTrue(plain.writeAtomically(path, bytes, WriteOptions(createParentDirectories = true, durability = Durability.Buffered)).isSuccess)
        return path
    }

    private fun tempsUnder(directory: String): List<String> =
        (plain.list(directory).getOrNull() ?: emptyList()).filter { FileNames.isTempName(it) }

    /** Every file of the tree under [directory] as relative-name to size, by the code under test only for listing. */
    private fun tree(directory: String): Map<String, Long> {
        val out: MutableMap<String, Long> = mutableMapOf()
        fun walk(path: String, prefix: String) {
            for (name: String in plain.list(path).getOrNull() ?: emptyList()) {
                val child: String = TestPaths.join(path, name)
                val meta: FileMetadata = assertNotNull(plain.metadata(child, followLinks = false))
                if (meta.kind == FileKind.Directory) {
                    out["$prefix$name/"] = 0L
                    walk(child, "$prefix$name/")
                } else {
                    out["$prefix$name"] = meta.sizeBytes
                }
            }
        }
        walk(directory, "")
        return out
    }

    /**
     * Runs [body] through withIo once per low-level operation index of the first dry run plus one: the job is cancelled
     * just before that operation. [verify] gets the index, whether a CancellationException reached the caller, and
     * the fault layer that observed the run. [fresh] rebuilds the state for each index.
     */
    private fun TestScope.everyCancelPoint(
        fresh: (FaultOps) -> Unit,
        body: FileAccess.(CancellationCheck) -> Unit,
        verify: (index: Int, cancelled: Boolean, total: Int) -> Unit,
    ) {
        var total: Int = -1
        var index: Int = 1
        while (total < 0 || index <= total + 1) {
            TestEnv.deleteTree(root)
            root = TestEnv.newRoot()
            val ops: FaultOps = FaultOps(TestEnv.realOps())
            fresh(ops)
            val files: FileAccess = fileAccessOver(ops)
            val dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
            var count: Int = 0
            val cancelAt: Int = if (total < 0) Int.MAX_VALUE else index
            lateinit var job: Job
            ops.onBefore = { _: Op, _: String -> if (++count == cancelAt) job.cancel() }
            var cancelled: Boolean = false
            job = launch(dispatcher, start = CoroutineStart.LAZY) {
                try {
                    files.withIo(dispatcher, body)
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
            job.start()
            testScheduler.advanceUntilIdle()
            if (total < 0) {
                total = count
                assertTrue(total > 3, "the dry run saw only $total operations")
                assertTrue(!cancelled, "dry run cancelled")
                verify(0, false, total)
            } else {
                assertEquals(index <= total, cancelled, "index $index of $total: cancelled=$cancelled")
                verify(index, cancelled, total)
                index++
            }
        }
    }

    @Test
    fun `writeAtomically cancelled at every step leaves the whole new content and no temp and reports the cancellation`() = runTest {
        val old: ByteArray = ByteArray(1000) { 1 }
        val new: ByteArray = ByteArray(200_000) { 2 }
        everyCancelPoint(
            fresh = { put("d/f", old) },
            body = { writeAtomically(p("d/f"), new, WriteOptions(durability = Durability.SyncedWithDirectory)) },
            verify = { index: Int, _: Boolean, total: Int ->
                val content: ByteArray? = plain.readBytes(p("d/f"), 1L shl 20).getOrNull()
                // Single-file operations never poll: the write completes, whatever the index.
                assertContentEquals(new, content, "index $index of $total")
                assertEquals(emptyList(), tempsUnder(p("d")), "index $index")
            },
        )
    }

    @Test
    fun `deleteRecursively cancelled at every step leaves a subset of the tree and can be run again`() = runTest {
        lateinit var original: Map<String, Long>
        everyCancelPoint(
            fresh = {
                for (name: String in listOf("a", "b", "s1/c", "s1/d", "s2/s3/e", "s2/f")) put("t/$name", ByteArray(10))
                original = tree(p("t"))
            },
            body = { cancellation: CancellationCheck -> deleteRecursively(p("t"), cancellation) },
            verify = { index: Int, _: Boolean, _: Int ->
                val left: Map<String, Long> = if (plain.metadata(p("t")) == null) emptyMap() else tree(p("t"))
                for (entry: String in left.keys) assertTrue(entry in original, "index $index: new entry $entry")
                assertTrue(plain.deleteRecursively(p("t")).isSuccess, "index $index: not resumable")
                assertNull(plain.metadata(p("t")))
            },
        )
    }

    @Test
    fun `treeSize and sweep cancelled at every step change nothing they should not and can be run again`() = runTest {
        everyCancelPoint(
            fresh = { for (name: String in listOf("a", "s1/c", "s2/d")) put("t/$name", ByteArray(7)) },
            body = { cancellation: CancellationCheck -> treeSize(p("t"), cancellation) },
            verify = { index: Int, _: Boolean, _: Int ->
                assertEquals(FileResult.Success(21L), plain.treeSize(p("t")), "index $index")
                assertEquals(setOf("a", "s1/", "s1/c", "s2/", "s2/d"), tree(p("t")).keys, "index $index")
            },
        )
        val old: Long = 1_000L
        everyCancelPoint(
            fresh = {
                for (name: String in listOf("a", "b", "c", "d")) {
                    assertTrue(TestEnv.setLastModified(put("s/$name", ByteArray(3)), old))
                }
            },
            body = { cancellation: CancellationCheck -> sweep(p("s"), 0L, cancellation) { true } },
            verify = { index: Int, _: Boolean, _: Int ->
                val left: Set<String> = tree(p("s")).keys
                assertTrue(left.all { it in setOf("a", "b", "c", "d") }, "index $index: $left")
                assertEquals(FileResult.Success(left.size), plain.sweep(p("s"), 0L, CancellationCheck.Never) { true }, "index $index")
                assertEquals(emptyList(), plain.list(p("s")).getOrNull(), "index $index")
            },
        )
    }

    @Test
    fun `a cross-volume move cancelled at every step leaves the source whole and either no destination or the whole one`() = runTest {
        val bytes: ByteArray = ByteArray(5 * 64 * 1024 + 11) { (it % 251).toByte() }
        var outcome: FileResult<Unit>? = null
        everyCancelPoint(
            fresh = { ops: FaultOps ->
                put("from", bytes)
                ops.failNext(Op.Rename, FileError.CrossDevice(p("from")), count = 1) { it == p("from") }
            },
            body = { cancellation: CancellationCheck -> outcome = move(p("from"), p("to"), MoveOptions(), cancellation) },
            verify = { index: Int, _: Boolean, _: Int ->
                assertEquals(emptyList(), tempsUnder(root), "index $index")
                val moved: FileResult<Unit>? = outcome
                val destination: ByteArray? = plain.readBytes(p("to"), 1L shl 22).getOrNull()
                val source: ByteArray? = plain.readBytes(p("from"), 1L shl 22).getOrNull()
                when (moved) {
                    is FileResult.Success -> {
                        assertContentEquals(bytes, destination, "index $index: moved")
                        assertNull(source, "index $index: the source of a finished move is gone")
                    }
                    is FileResult.Failure -> {
                        assertEquals(FileError.Cancelled, moved.error, "index $index")
                        assertNull(destination, "index $index: half-written destination")
                        assertContentEquals(bytes, source, "index $index: the source of a cancelled move")
                    }
                    null -> fail("index $index: the body did not run to its result")
                }
                outcome = null
            },
        )
    }

    // ---- what the caller hands in -----------------------------------------------------------------------------------

    @Test
    fun `a match lambda of sweep that throws propagates unchanged and leaves no handle and a usable engine`() {
        for (name: String in listOf("a", "b", "c", "d")) {
            assertTrue(TestEnv.setLastModified(put("s/$name", ByteArray(3)), 1_000L))
        }
        var calls: Int = 0
        val boom: IllegalStateException = IllegalStateException("match failed")
        val thrown: IllegalStateException = assertFailsWith { plain.sweep(p("s"), 0L, CancellationCheck.Never) { if (++calls == 3) throw boom else false } }
        assertTrue(thrown === boom)
        assertEquals(listOf("a", "b", "c", "d"), plain.list(p("s")).getOrNull())
        assertEquals(FileResult.Success(4), fileAccessOver(TestEnv.realOps()).sweep(p("s"), 0L, CancellationCheck.Never) { true })
    }

    @Test
    fun `a cancellation check that throws never escapes the call and a cross-volume copy still removes its temp`() {
        val bytes: ByteArray = ByteArray(3 * 64 * 1024) { 5 }
        val from: String = put("from", bytes)
        for (throwAt: Int in 1..4) {
            val ops: FaultOps = FaultOps(TestEnv.realOps())
            ops.failNext(Op.Rename, FileError.CrossDevice(from), count = 1) { it == from }
            var polls: Int = 0
            val result: FileResult<Unit> = fileAccessOver(ops).move(from, p("to")) { if (++polls == throwAt) throw IllegalStateException("check $throwAt") else false }
            assertTrue(result is FileResult.Failure, "throwAt $throwAt: $result")
            assertEquals(emptyList(), tempsUnder(root), "throwAt $throwAt")
            assertContentEquals(bytes, plain.readBytes(from, 1L shl 22).getOrNull(), "throwAt $throwAt")
            assertNull(plain.metadata(p("to")), "throwAt $throwAt")
        }
        put("t/a", ByteArray(2))
        for (throwAt: Int in 1..3) {
            var polls: Int = 0
            val deleted: FileResult<Unit> = plain.deleteRecursively(p("t")) { if (++polls == throwAt) throw IllegalStateException("check") else false }
            assertTrue(deleted is FileResult.Failure, "deleteRecursively throwAt $throwAt: $deleted")
            var polls2: Int = 0
            val sized: FileResult<Long> = plain.treeSize(p("t")) { if (++polls2 == throwAt) throw IllegalStateException("check") else false }
            assertTrue(sized is FileResult.Failure || sized is FileResult.Success, "treeSize throwAt $throwAt")
        }
    }

    @Test
    fun `a withIo block that throws propagates the exception and does not cancel the caller`() = runTest {
        val files: FileAccess = fileAccessOver(TestEnv.realOps())
        val dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val thrown: IllegalArgumentException = assertFailsWith { files.withIo(dispatcher) { throw IllegalArgumentException("block") } }
        assertEquals("block", thrown.message)
        assertEquals(7, files.withIo(dispatcher) { 7 })
    }

    @Test
    fun `a KeyedMutex key is free again after the action threw or the caller was cancelled while waiting or holding`() = runTest {
        val mutex: KeyedMutex<String> = KeyedMutex()
        assertFailsWith<IllegalStateException> { mutex.withLock("k") { throw IllegalStateException("action") } }
        assertEquals(0, mutex.activeKeyCount)

        val holding: CompletableDeferred<Unit> = CompletableDeferred()
        val never: CompletableDeferred<Unit> = CompletableDeferred()
        val holder: Job = launch { mutex.withLock("k") { holding.complete(Unit); never.await() } }
        holding.await()
        val waiters: List<Job> = List(5) { launch { mutex.withLock("k") { } } }
        testScheduler.advanceUntilIdle()
        assertEquals(1, mutex.activeKeyCount)
        waiters[1].cancelAndJoin()
        waiters[3].cancelAndJoin()
        holder.cancelAndJoin()
        withTimeout(5_000) { waiters.forEach { it.join() } }
        assertEquals(0, mutex.activeKeyCount)
        assertTrue(mutex.tryWithLock("k") { })
        // the action of a nested withIo that is cancelled releases the key too
        val files: FileAccess = fileAccessOver(TestEnv.realOps())
        val dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val job: Job = launch {
            mutex.withLock("k") { files.withIo(dispatcher) { entered.complete(Unit); } ; never.await() }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, mutex.activeKeyCount)
        val after: Boolean = async { mutex.withLock("k") { true } }.await()
        assertTrue(after)
    }

    @Test
    fun `PathLocks releases a path after a failing action and after cancellation`() = runTest {
        val path: String = p("locked")
        assertFailsWith<IllegalStateException> { PathLocks.withLock(path) { throw IllegalStateException("x") } }
        val held: CompletableDeferred<Unit> = CompletableDeferred()
        val job: Job = launch { PathLocks.withLock(path) { held.complete(Unit); CompletableDeferred<Unit>().await() } }
        held.await()
        assertTrue(!PathLocks.tryWithLock(path) { })
        job.cancelAndJoin()
        assertTrue(PathLocks.tryWithLock(path) { })
    }

    @Test
    fun `a second engine created after the first was dropped works on the same files`() {
        var first: FileAccess? = createFileAccess()
        assertTrue(first!!.writeAtomically(p("f"), byteArrayOf(1, 2, 3)).isSuccess)
        first = null
        val second: FileAccess = createFileAccess()
        assertContentEquals(byteArrayOf(1, 2, 3), second.readBytes(p("f"), 10).getOrNull())
        assertTrue(second.writeAtomically(p("f"), byteArrayOf(9)).isSuccess)
        assertContentEquals(byteArrayOf(9), createFileAccess().readBytes(p("f"), 10).getOrNull())
        assertEquals(emptyList(), tempsUnder(root))
    }
}
