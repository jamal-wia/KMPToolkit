package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 4 of the breaker for unit G (character: consumers under concurrency). The access patterns of the consumer
 * modules, with real threads on one directory of the real file system: many writers and readers of one destination
 * (storage, uploader outcomes with a leftovers directory), the no-replace move that claims a destination (downloader),
 * delete and replace during a read, concurrent recursive deletes, sweep next to writers, and a read-modify-write
 * cycle under PathLocks. No sleep and no timing assertion: threads meet at barriers, and every wait has a real-time
 * bound that fails with its own message (a hang is a defect, not a slow test).
 */
class ConcurrencyR4Test {
    private val files: FileAccess = createFileAccess()
    private lateinit var root: File
    private lateinit var pool: ExecutorService

    // path-hygiene-ignore: PH1 the separator is read only to skip the open-file test on a Windows host, where an open file legitimately blocks a replace (documented); it is never joined into a path
    private val windowsHost: Boolean = File.separatorChar == '\\'

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kmptoolkit-concurrency-r4").toRealPath().toFile()
        pool = Executors.newFixedThreadPool(16)
    }

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
        root.deleteRecursively()
    }

    private fun <T> Future<T>.await(what: String): T =
        try {
            get(60, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("HANG: $what did not finish in 60 s", e)
        }

    private fun uniform(byte: Int, size: Int): ByteArray = ByteArray(size) { byte.toByte() }

    private fun isWhole(bytes: ByteArray, size: Int): Boolean = bytes.size == size && bytes.all { it == bytes[0] }

    /** Runs [bodies] at the same moment, each on its own thread, and returns their results. */
    private fun <T> together(what: String, bodies: List<() -> T>): List<T> {
        val barrier: CyclicBarrier = CyclicBarrier(bodies.size)
        val futures: List<Future<T>> = bodies.map { body: () -> T -> pool.submit<T> { barrier.await(30, TimeUnit.SECONDS); body() } }
        return futures.map { it.await(what) }
    }

    private fun writersAndReaders(destinationDirectory: File, options: WriteOptions) {
        val destination: String = File(destinationDirectory, "dest").path
        val size: Int = 64 * 1024
        assertTrue(files.writeAtomically(destination, uniform(0, size), options) is FileResult.Success<*>)
        val writersDone: AtomicBoolean = AtomicBoolean(false)
        val badReads: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val reads: AtomicInteger = AtomicInteger()
        val readers: List<Future<Unit>> = (0 until 4).map {
            pool.submit<Unit> {
                while (!writersDone.get()) {
                    when (val read: FileResult<ByteArray> = files.readBytes(destination, 1L shl 20)) {
                        is FileResult.Failure -> badReads += "read failed: ${read.error}"
                        is FileResult.Success -> if (!isWhole(read.value, size)) badReads += "torn read: size ${read.value.size}"
                    }
                    reads.incrementAndGet()
                }
            }
        }
        val failures: List<Int> = together("eight writers", (1..8).map { id: Int ->
            { var failed: Int = 0; repeat(40) { if (files.writeAtomically(destination, uniform(id, size), options) !is FileResult.Success<*>) failed++ }; failed }
        })
        writersDone.set(true)
        readers.forEach { it.await("reader") }
        assertEquals(List(8) { 0 }, failures, "a writer failed although writers never make each other fail")
        assertEquals(emptyList(), badReads.take(3), "readers must see the previous or the whole new content, never a failure or a mix")
        assertTrue(reads.get() > 0)
        val finalContent: ByteArray = File(destination).readBytes()
        assertTrue(isWhole(finalContent, size) && finalContent[0] in 1..8, "the last rename wins with one whole payload")
        assertEquals(listOf("dest"), destinationDirectory.list()!!.sorted().filter { it != "leftovers" }, "a temporary file was left behind")
        File(destinationDirectory, "leftovers").takeIf { it.exists() }?.let { assertEquals(emptyList(), it.list()!!.toList(), "temporary files left in the leftovers directory") }
    }

    @Test
    fun `eight writers and four readers on one destination never see a mix or a failure`() {
        writersAndReaders(root, WriteOptions(durability = Durability.Buffered))
    }

    @Test
    fun `the same with a leftovers directory as the uploader outcome storage uses it`() {
        val directory: File = File(root, "outcomes").also { it.mkdirs() }
        writersAndReaders(directory, WriteOptions(tempDirectory = File(directory, "leftovers").path, createParentDirectories = true))
    }

    @Test
    fun `no replace writers to one destination give exactly one winner and the winner's content`() {
        repeat(30) { round: Int ->
            val destination: String = File(root, "w$round").path
            val results: List<FileResult<Unit>> = together("no-replace writers round $round", (1..8).map { id: Int ->
                { files.writeAtomically(destination, uniform(id, 4096), WriteOptions(existing = ExistingFile.Fail, durability = Durability.Buffered)) }
            })
            assertEquals(1, results.count { it is FileResult.Success<*> }, "round $round: exactly one writer wins: $results")
            assertTrue(results.filterIsInstance<FileResult.Failure>().all { it.error is FileError.AlreadyExists }, "round $round: losers get AlreadyExists: $results")
            assertTrue(isWhole(File(destination).readBytes(), 4096))
        }
        assertEquals(30, root.list()!!.size, "a temporary name was left next to the destinations: ${root.list()!!.sorted()}")
    }

    @Test
    fun `no replace moves onto one destination claim it exactly once and leave the losers' sources whole`() {
        repeat(30) { round: Int ->
            val destination: String = File(root, "claim$round").path
            val sources: List<File> = (1..8).map { id: Int -> File(root, "src$round-$id").also { it.writeBytes(uniform(id, 2048)) } }
            val results: List<FileResult<Unit>> = together("claiming moves round $round", sources.map { source: File ->
                { files.move(source.path, destination, MoveOptions(existing = ExistingFile.Fail, acrossVolumes = false)) }
            })
            val winners: List<Int> = results.indices.filter { results[it] is FileResult.Success<*> }
            assertEquals(1, winners.size, "round $round: exactly one claim wins: $results")
            for (index: Int in sources.indices) {
                if (index == winners.single()) {
                    assertTrue(!sources[index].exists(), "round $round: the winner's source is gone")
                    assertEquals(uniform(index + 1, 2048).toList(), File(destination).readBytes().toList(), "round $round: the destination holds the winner's bytes")
                } else {
                    assertTrue(results[index].errorOrNull() is FileError.AlreadyExists, "round $round: loser $index: ${results[index]}")
                    assertEquals(uniform(index + 1, 2048).toList(), sources[index].readBytes().toList(), "round $round: a loser's source must stay whole")
                }
            }
        }
        assertEquals(30 + 30 * 7, root.list()!!.size, "a temporary or hard link name was left behind: ${root.list()!!.sorted().filter { !it.startsWith("src") && !it.startsWith("claim") }}")
    }

    @Test
    fun `replacing moves from many sources onto one destination all succeed and the destination is one whole source`() {
        repeat(30) { round: Int ->
            val destination: String = File(root, "dest$round").path
            File(destination).writeBytes(uniform(0, 2048))
            val sources: List<File> = (1..8).map { id: Int -> File(root, "rs$round-$id").also { it.writeBytes(uniform(id, 2048)) } }
            val results: List<FileResult<Unit>> = together("replacing moves round $round", sources.map { source: File -> { files.move(source.path, destination) } })
            assertTrue(results.all { it is FileResult.Success<*> }, "round $round: $results")
            assertTrue(sources.none { it.exists() }, "round $round: every source was consumed")
            val content: ByteArray = File(destination).readBytes()
            assertTrue(isWhole(content, 2048) && content[0] in 1..8, "round $round: the destination is one whole source")
        }
    }

    @Test
    fun `delete and replace during a read give the whole old content, the whole new content, or NotFound`() {
        val size: Int = 2 * 1024 * 1024
        val target: File = File(root, "big")
        repeat(60) { round: Int ->
            target.writeBytes(uniform(1, size))
            val replacement: File = File(root, "next").also { it.writeBytes(uniform(2, size)) }
            val outcome: List<Any?> = together("read versus change round $round", listOf(
                { files.readBytes(target.path, size.toLong()) },
                {
                    when (round % 3) {
                        0 -> files.delete(target.path)
                        1 -> files.move(replacement.path, target.path)
                        else -> files.deleteRecursively(target.path)
                    }
                },
            ))
            @Suppress("UNCHECKED_CAST")
            val read: FileResult<ByteArray> = outcome[0] as FileResult<ByteArray>
            when (read) {
                is FileResult.Success -> assertTrue(isWhole(read.value, size) && read.value[0] in 1..2, "round $round: torn read")
                is FileResult.Failure -> assertTrue(read.error is FileError.NotFound, "round $round: ${read.error}")
            }
            assertTrue(outcome[1] is FileResult.Success<*>, "round $round: the changing side failed: ${outcome[1]}")
            replacement.delete()
            target.delete()
        }
    }

    @Test
    fun `a move over an open file leaves the open reader on the whole old content`() {
        if (windowsHost) return // documented: on Windows an open file can make the replace fail with AccessDenied
        val target: File = File(root, "open")
        target.writeBytes(uniform(1, 100_000))
        FileInputStream(target).use { open: FileInputStream ->
            val first: ByteArray = open.readNBytes(10)
            File(root, "new").writeBytes(uniform(2, 100_000))
            assertTrue(files.move(File(root, "new").path, target.path) is FileResult.Success<*>)
            assertTrue(files.writeAtomically(target.path, uniform(3, 100_000)) is FileResult.Success<*>)
            val rest: ByteArray = open.readAllBytes()
            assertTrue(isWhole(first + rest, 100_000) && first[0] == 1.toByte(), "the open handle keeps the old inode, whole")
        }
        assertTrue(files.delete(target.path) is FileResult.Success<*>)
        assertEquals(emptyList(), root.list()!!.toList())
    }

    @Test
    fun `two recursive deletes of one tree both succeed while a size walk never fails`() {
        repeat(25) { round: Int ->
            val tree: File = File(root, "tree$round")
            for (a: Int in 0 until 4) for (b: Int in 0 until 4) {
                File(tree, "d$a/e$b").mkdirs()
                for (f: Int in 0 until 5) File(tree, "d$a/e$b/f$f").writeText("x$f")
            }
            val results: List<Any?> = together("recursive deletes round $round", listOf(
                { files.deleteRecursively(tree.path) },
                { files.deleteRecursively(tree.path) },
                { files.treeSize(tree.path) },
                { files.treeSize(tree.path) },
            ))
            assertTrue(results[0] is FileResult.Success<*> && results[1] is FileResult.Success<*>, "round $round: both deletes succeed: ${results.take(2)}")
            assertTrue(results[2] is FileResult.Success<*> && results[3] is FileResult.Success<*>, "round $round: treeSize fails only with Cancelled or InvalidPath: ${results.drop(2)}")
            assertTrue(!tree.exists(), "round $round: nothing is left")
        }
    }

    @Test
    fun `sweep next to running writers never removes a fresh temporary file`() {
        val directory: File = File(root, "sweepdir").also { it.mkdirs() }
        val writersDone: AtomicBoolean = AtomicBoolean(false)
        val deleted: AtomicInteger = AtomicInteger()
        val sweepFailures: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val sweeper: Future<Unit> = pool.submit<Unit> {
            while (!writersDone.get()) {
                when (val swept: FileResult<Int> = files.sweep(directory.path, olderThanMillis = 60_000L) { FileNames.isTempName(it) }) {
                    is FileResult.Success -> deleted.addAndGet(swept.value)
                    is FileResult.Failure -> sweepFailures += swept.error.toString()
                }
            }
        }
        val failures: List<Int> = together("writers beside a sweeper", (0 until 6).map { id: Int ->
            { var failed: Int = 0; repeat(150) { if (files.writeAtomically(File(directory, "f$id").path, uniform(id, 512), WriteOptions(durability = Durability.Buffered)) !is FileResult.Success<*>) failed++ }; failed }
        })
        writersDone.set(true)
        sweeper.await("sweeper")
        assertEquals(List(6) { 0 }, failures, "a sweep removed a temporary file of a write in flight")
        assertEquals(emptyList(), sweepFailures.take(3))
        assertEquals(0, deleted.get(), "sweep removed a file younger than olderThanMillis")
        assertEquals((0 until 6).map { "f$it" }, directory.list()!!.sorted())
    }

    @Test
    fun `a read modify write cycle under PathLocks loses no update whatever the spelling of the path`() = runBlocking {
        val counter: File = File(root, "counter").also { it.writeText("0") }
        // path-hygiene-ignore: PH4 the spellings differ on purpose in separators and dot segments; the lock key folds them, the IO uses the plain path
        val spellings: List<String> = listOf(counter.path, root.path + "/./counter", root.path + "//counter", root.path + "/x/../counter")
        withTimeout(60_000) {
            (0 until 8).map { worker: Int ->
                async(Dispatchers.Default) {
                    repeat(50) {
                        val path: String = spellings[(worker + it) % spellings.size]
                        PathLocks.withLock(path) {
                            files.withIo(Dispatchers.IO) {
                                val current: Long = readBytes(counter.path, 32).getOrNull()?.decodeToString()?.toLongOrNull() ?: -1_000_000L
                                writeAtomically(counter.path, (current + 1).toString().encodeToByteArray(), WriteOptions(durability = Durability.Buffered))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        assertEquals("400", counter.readText(), "an update was lost under the lock")
    }
}
