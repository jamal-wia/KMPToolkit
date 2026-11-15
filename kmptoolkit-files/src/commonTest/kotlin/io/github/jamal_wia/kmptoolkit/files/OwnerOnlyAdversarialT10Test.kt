@file:OptIn(ToolkitInternalApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * T10 adversarial half, in the style of `CancellationLifecycleR3Test`, `FailureInjectionR3Test` and
 * `ConcurrencyStormR6Test`: the owner-only creation path (temp file, created parents, temp directory) under
 * cancellation at every low-level step, an injected failure at the owner-only entry points, concurrent writers from a
 * fixed seed, and a fresh instance after a finished one. After each: no temp file, no half-written destination, no
 * open descriptor. No sleep: the cancel happens exactly between two steps on a test dispatcher.
 */
class OwnerOnlyAdversarialT10Test {
    private lateinit var root: String
    private var descriptorsBefore: Long? = null

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        descriptorsBefore = TestEnv.openDescriptorCount()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
        val before: Long? = descriptorsBefore
        val after: Long? = TestEnv.openDescriptorCount()
        if (before != null && after != null) assertTrue(after - before <= 3, "descriptors went from $before to $after")
    }

    private fun p(logical: String): String = TestPaths.join(root, logical)

    private fun tempsUnder(files: FileAccess, directory: String): List<String> =
        (files.list(directory).getOrNull() ?: emptyList()).filter { FileNames.isTempName(it) }

    /** Runs [body] through withIo once per low-level operation of a dry run plus one, cancelling just before that operation. */
    private fun TestScope.everyCancelPoint(
        body: FileAccess.(CancellationCheck) -> Unit,
        verify: (index: Int, files: FileAccess) -> Unit,
    ) {
        var total: Int = -1
        var index: Int = 1
        while (total < 0 || index <= total + 1) {
            TestEnv.deleteTree(root)
            root = TestEnv.newRoot()
            val ops: FaultOps = FaultOps(TestEnv.realOps())
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
                verify(0, fileAccessOver(TestEnv.realOps()))
            } else {
                assertEquals(index <= total, cancelled, "index $index of $total: cancelled=$cancelled")
                verify(index, fileAccessOver(TestEnv.realOps()))
                index++
            }
        }
    }

    @Test
    fun `an ownerOnly write cancelled at every step leaves the whole new content and no temp`() = runTest {
        val new: ByteArray = ByteArray(50_000) { 2 }
        everyCancelPoint(
            body = {
                writeAtomically(
                    p("a/b/f"),
                    new,
                    WriteOptions(
                        durability = Durability.SyncedWithDirectory,
                        createParentDirectories = true,
                        tempDirectory = p("scratch"),
                        ownerOnly = true,
                    ),
                )
            },
            verify = { index: Int, files: FileAccess ->
                // A single-file operation never polls the cancellation: the write completes, whatever the index.
                assertContentEquals(new, files.readBytes(p("a/b/f"), 1L shl 20).getOrNull(), "index $index")
                assertEquals(emptyList(), tempsUnder(files, p("a/b")), "index $index")
                assertEquals(emptyList(), tempsUnder(files, p("scratch")), "index $index")
            },
        )
    }

    @Test
    fun `createDirectories with ownerOnly cancelled at every step leaves a directory chain that can be created again`() = runTest {
        everyCancelPoint(
            body = { createDirectories(p("x/y/z"), durable = true, ownerOnly = true) },
            verify = { index: Int, files: FileAccess ->
                assertTrue(files.createDirectories(p("x/y/z"), ownerOnly = true).isSuccess, "index $index: not repeatable")
                assertEquals(FileKind.Directory, files.metadata(p("x/y/z"))?.kind, "index $index")
            },
        )
    }

    @Test
    fun `a failure of the owner only temp creation is typed and leaves nothing behind`() {
        for (error: FileError in listOf(FileError.AccessDenied(p("f")), FileError.NoSpace(p("f")), FileError.Unsupported(p("f")))) {
            val ops: FaultOps = FaultOps(TestEnv.realOps())
            val files: FileAccess = fileAccessOver(ops)
            ops.failNext(Op.CreateNew, error)
            val result: FileResult<Unit> = files.writeAtomically(p("f"), byteArrayOf(1), WriteOptions(ownerOnly = true))
            // The engine names the directory of the temp file for a failed creation (existing behaviour), not the destination.
            assertEquals(error::class, result.errorOrNull()?.let { it::class }, "$error: $result")
            assertNull(files.metadata(p("f")), "$error: no destination")
            assertEquals(emptyList(), tempsUnder(files, root), "$error")
        }
    }

    @Test
    fun `a failure creating an owner only parent is typed and the write creates no file`() {
        val ops: FaultOps = FaultOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        ops.failNext(Op.CreateDirectory, FileError.AccessDenied(p("a")))
        val result: FileResult<Unit> =
            files.writeAtomically(p("a/f"), byteArrayOf(1), WriteOptions(createParentDirectories = true, ownerOnly = true))
        assertEquals(FileError.AccessDenied(p("a")), result.errorOrNull())
        assertNull(files.metadata(p("a/f")))
        assertEquals(emptyList(), tempsUnder(files, root))
    }

    @Test
    fun `a rename that fails after an owner only temp was created removes the temp`() {
        val ops: FaultOps = FaultOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        ops.failNext(Op.Rename, FileError.AccessDenied(p("f")))
        val result: FileResult<Unit> = files.writeAtomically(p("f"), byteArrayOf(1), WriteOptions(ownerOnly = true))
        assertEquals(FileError.AccessDenied(p("f")), result.errorOrNull())
        assertEquals(emptyList(), tempsUnder(files, root))
        assertNull(files.metadata(p("f")))
    }

    /** Self-validating content: its length, then that many copies of one char. */
    private fun payload(random: Random): ByteArray {
        val length: Int = random.nextInt(0, 8192)
        val ch: Char = 'a' + random.nextInt(26)
        return "$length|$ch|${ch.toString().repeat(length)}".encodeToByteArray()
    }

    private fun isWhole(bytes: ByteArray): Boolean {
        val text: String = bytes.decodeToString()
        val first: Int = text.indexOf('|')
        if (first <= 0 || text.indexOf('|', first + 1) != first + 2) return false
        val length: Int = text.substring(0, first).toIntOrNull() ?: return false
        val ch: Char = text[first + 1]
        val body: String = text.substring(first + 3)
        return body.length == length && body.all { it == ch }
    }

    @Test
    fun `concurrent ownerOnly writers and directory creators through two instances stay whole and leave no temp`() =
        runTest(timeout = 90.seconds) {
            val seed: Int = 1005
            val instances: List<FileAccess> = listOf(fileAccessOver(TestEnv.realOps()), fileAccessOver(TestEnv.realOps()))
            val unexpected: AtomicInt = AtomicInt(0)
            val names: List<String> = listOf("a.dat", "b.dat", "n/c.dat", "n/m/d.dat")
            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope {
                        repeat(12) { worker: Int ->
                            launch {
                                val random: Random = Random(seed * 100 + worker)
                                repeat(80) {
                                    val files: FileAccess = instances[random.nextInt(instances.size)]
                                    val path: String = p(names[random.nextInt(names.size)])
                                    if (random.nextInt(4) == 0) {
                                        val dir: FileResult<Unit> = files.createDirectories(p("n/m/k${random.nextInt(3)}"), ownerOnly = true)
                                        if (dir !is FileResult.Success) unexpected.addAndFetch(1)
                                    } else {
                                        val options: WriteOptions = WriteOptions(
                                            durability = Durability.Buffered,
                                            createParentDirectories = true,
                                            ownerOnly = true,
                                        )
                                        val result: FileResult<Unit> = files.writeAtomically(path, payload(random), options)
                                        if (result !is FileResult.Success) unexpected.addAndFetch(1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (unexpected.load() != 0) fail("seed=$seed: ${unexpected.load()} calls failed")
            for (name: String in names) {
                val bytes: ByteArray? = instances[0].readBytes(p(name), 1_000_000).getOrNull()
                if (bytes != null && !isWhole(bytes)) fail("seed=$seed: $name is torn (${bytes.size} bytes)")
            }
            assertEquals(0, instances[0].sweep(root, 0) { FileNames.isTempName(it) }.getOrNull(), "seed=$seed: a temp file survived")
        }

    @Test
    fun `a fresh instance after a finished one writes owner only again over the same directory`() {
        val first: FileAccess = fileAccessOver(TestEnv.realOps())
        assertTrue(first.writeAtomically(p("d/f"), byteArrayOf(1), WriteOptions(createParentDirectories = true, ownerOnly = true)).isSuccess)
        val second: FileAccess = fileAccessOver(TestEnv.realOps())
        assertTrue(second.writeAtomically(p("d/f"), byteArrayOf(2), WriteOptions(ownerOnly = true)).isSuccess)
        assertContentEquals(byteArrayOf(2), first.readBytes(p("d/f"), 16).getOrNull())
        assertTrue(second.createDirectories(p("d"), ownerOnly = true).isSuccess)
    }
}
