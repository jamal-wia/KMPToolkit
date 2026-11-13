package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Round 6 of the breaker for unit G, Q2: a mixed write/replace/move/copy-by-move/delete/list/read storm on a small set
 * of paths, through ONE [FileAccess] and through TWO instances over the same directory. Every assertion is a sentence of
 * the FileAccess KDoc: "Nothing is serialized inside: concurrent writers of one path each succeed and the last rename
 * wins", an atomic write is never seen torn or empty, a failure is typed, no temporary file survives a finished call,
 * and a read-modify-write under [PathLocks] loses no update (the lock is process-wide, so it holds across instances,
 * unlike a private [KeyedMutex] per instance, which promises nothing across them).
 */
@OptIn(ExperimentalAtomicApi::class)
class ConcurrencyStormR6Test {
    private lateinit var root: String

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private val names: List<String> = listOf("a.dat", "b.dat", "c.dat", "d.dat")

    private fun p(name: String): String = TestPaths.join(root, name)

    /** Self-validating content: `<length>|<char>|` followed by exactly that many copies of the char. */
    private fun payload(random: Random): ByteArray {
        val length: Int = when (random.nextInt(4)) {
            0 -> 0
            1 -> random.nextInt(1, 64)
            2 -> random.nextInt(64, 4096)
            else -> random.nextInt(4096, 40_000)
        }
        val ch: Char = 'a' + random.nextInt(26)
        return "$length|$ch|${ch.toString().repeat(length)}".encodeToByteArray()
    }

    private fun isWhole(bytes: ByteArray): Boolean {
        val text: String = bytes.decodeToString()
        val first: Int = text.indexOf('|')
        if (first <= 0) return false
        val second: Int = text.indexOf('|', first + 1)
        if (second != first + 2) return false
        val length: Int = text.substring(0, first).toIntOrNull() ?: return false
        val ch: Char = text[first + 1]
        return text.length - (second + 1) == length && text.substring(second + 1).all { it == ch }
    }

    private fun check(condition: Boolean, seed: Int, what: () -> String) {
        if (!condition) fail("seed=$seed: ${what()}")
    }

    private suspend fun storm(instances: List<FileAccess>, workers: Int, opsPerWorker: Int, seed: Int) {
        val unexpected: AtomicInt = AtomicInt(0)
        val problems: Channel<String> = Channel(Channel.UNLIMITED)
        fun problem(text: String) {
            unexpected.addAndFetch(1)
            problems.trySend(text)
        }
        withContext(Dispatchers.Default) {
            withTimeout(50.seconds) {
                coroutineScope {
                    repeat(workers) { worker: Int ->
                        launch {
                            val random: Random = Random(seed * 1000 + worker)
                            repeat(opsPerWorker) {
                                val files: FileAccess = instances[random.nextInt(instances.size)]
                                val oneIndex: Int = random.nextInt(names.size)
                                val one: String = p(names[oneIndex])
                                val other: String = p(names[(oneIndex + 1 + random.nextInt(names.size - 1)) % names.size])
                                when (random.nextInt(9)) {
                                    0, 1 -> {
                                        val result: FileResult<Unit> = files.writeAtomically(one, payload(random))
                                        if (result !is FileResult.Success) problem("replace write of $one: $result")
                                    }
                                    2 -> {
                                        val result: FileResult<Unit> =
                                            files.writeAtomically(one, payload(random), WriteOptions(existing = ExistingFile.Fail))
                                        val error: FileError? = result.errorOrNull()
                                        if (error != null && error != FileError.AlreadyExists(one)) problem("no-replace write of $one: $result")
                                    }
                                    3 -> {
                                        val result: FileResult<Unit> = files.move(one, other, MoveOptions())
                                        val error: FileError? = result.errorOrNull()
                                        if (error != null && error !is FileError.NotFound) problem("replace move $one -> $other: $result")
                                    }
                                    4 -> {
                                        val result: FileResult<Unit> = files.move(one, other, MoveOptions(existing = ExistingFile.Fail))
                                        val error: FileError? = result.errorOrNull()
                                        if (error != null && error !is FileError.NotFound && error != FileError.AlreadyExists(other)) {
                                            problem("no-replace move $one -> $other: $result")
                                        }
                                    }
                                    5 -> {
                                        val result: FileResult<Unit> = files.delete(one)
                                        if (result !is FileResult.Success) problem("delete of $one: $result")
                                    }
                                    6 -> {
                                        val result: FileResult<ByteArray> = files.readBytes(one, 1_000_000)
                                        val error: FileError? = result.errorOrNull()
                                        if (error != null && error != FileError.NotFound(one)) problem("read of $one: $result")
                                        val bytes: ByteArray? = result.getOrNull()
                                        if (bytes != null && !isWhole(bytes)) problem("read of $one saw a torn or empty file of ${bytes.size} bytes")
                                    }
                                    7 -> {
                                        val listed: List<String>? = files.list(root).getOrNull()
                                        if (listed == null) {
                                            problem("list failed")
                                        } else {
                                            if (listed != listed.sorted() || listed.toSet().size != listed.size) problem("list not sorted or has duplicates: $listed")
                                            val strange: List<String> = listed.filter { it !in names && !FileNames.isTempName(it) }
                                            if (strange.isNotEmpty()) problem("list holds foreign entries: $strange")
                                        }
                                    }
                                    else -> {
                                        val meta: FileMetadata? = files.metadata(one)
                                        if (meta != null && meta.kind != FileKind.RegularFile) problem("metadata of $one: ${meta.kind}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val firstProblem: List<String> = List(5) { problems.tryReceive().getOrNull() }.filterNotNull()
        check(unexpected.load() == 0, seed) { "${unexpected.load()} untyped or torn outcomes, first: $firstProblem" }
        // Quiescent: every call has returned, so no temporary file and no foreign entry may be left, every entry whole.
        val left: List<String> = instances[0].list(root).getOrNull().orEmpty()
        check(left.all { it in names }, seed) { "entries after the storm: $left" }
        for (name: String in left) {
            val bytes: ByteArray = instances[0].readBytes(p(name), 1_000_000).getOrNull() ?: fail("seed=$seed: $name unreadable after the storm")
            check(isWhole(bytes), seed) { "$name is torn after the storm (${bytes.size} bytes)" }
        }
        // Last rename wins: a plain sequential write after the storm is what a reader sees through every instance.
        for (files: FileAccess in instances) {
            val final: ByteArray = "5|z|zzzzz".encodeToByteArray()
            assertTrue(files.writeAtomically(p("a.dat"), final).isSuccess)
            for (reader: FileAccess in instances) assertEquals("5|z|zzzzz", reader.readBytes(p("a.dat"), 100).getOrNull()?.decodeToString())
        }
        assertEquals(0, instances[0].sweep(root, 0) { FileNames.isTempName(it) }.getOrNull(), "seed=$seed: sweep found a temporary file")
    }

    @Test
    fun `sixteen coroutines of mixed operations through one instance keep every documented promise`() = runTest(timeout = 90.seconds) {
        storm(listOf(createFileAccess()), workers = 16, opsPerWorker = 250, seed = 61)
    }

    @Test
    fun `thirty two coroutines of mixed operations through one instance keep every documented promise`() = runTest(timeout = 90.seconds) {
        storm(listOf(createFileAccess()), workers = 32, opsPerWorker = 150, seed = 62)
    }

    @Test
    fun `sixteen coroutines of mixed operations through two instances over one directory keep every documented promise`() = runTest(timeout = 90.seconds) {
        storm(listOf(createFileAccess(), createFileAccess()), workers = 16, opsPerWorker = 250, seed = 63)
    }

    @Test
    fun `thirty two coroutines of mixed operations through two instances over one directory keep every documented promise`() = runTest(timeout = 90.seconds) {
        storm(listOf(createFileAccess(), createFileAccess()), workers = 32, opsPerWorker = 150, seed = 64)
    }

    private fun readCounter(files: FileAccess, path: String): Int =
        files.readBytes(path, 100).getOrNull()?.decodeToString()?.toIntOrNull() ?: 0

    @Test
    fun `a read modify write under PathLocks loses no update through one instance and through two`() = runTest(timeout = 90.seconds) {
        for (twoInstances: Boolean in listOf(false, true)) {
            val first: FileAccess = createFileAccess()
            val instances: List<FileAccess> = if (twoInstances) listOf(first, createFileAccess()) else listOf(first)
            val path: String = p(if (twoInstances) "two.cnt" else "one.cnt")
            val workers = 24
            val perWorker = 40
            withContext(Dispatchers.Default) {
                withTimeout(50.seconds) {
                    coroutineScope {
                        repeat(workers) { worker: Int ->
                            launch {
                                repeat(perWorker) {
                                    val files: FileAccess = instances[(worker + it) % instances.size]
                                    PathLocks.withLock(path) {
                                        val next: Int = readCounter(files, path) + 1
                                        val result: FileResult<Unit> = files.writeAtomically(path, next.toString().encodeToByteArray())
                                        if (!result.isSuccess) fail("counter write failed: $result")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(workers * perWorker, readCounter(first, path), "lost updates with twoInstances=$twoInstances")
        }
    }

    @Test
    fun `without a lock two instances still never tear the counter and a private lock per instance promises nothing across them`() = runTest(timeout = 90.seconds) {
        val a: FileAccess = createFileAccess()
        val b: FileAccess = createFileAccess()
        val path: String = p("loose.cnt")
        val workers = 16
        val perWorker = 30
        val mutexA: KeyedMutex<String> = KeyedMutex()
        val mutexB: KeyedMutex<String> = KeyedMutex()
        withContext(Dispatchers.Default) {
            withTimeout(50.seconds) {
                coroutineScope {
                    repeat(workers) { worker: Int ->
                        launch {
                            repeat(perWorker) {
                                val useA: Boolean = (worker + it) % 2 == 0
                                val files: FileAccess = if (useA) a else b
                                (if (useA) mutexA else mutexB).withLock("k") {
                                    val next: Int = readCounter(files, path) + 1
                                    val result: FileResult<Unit> = files.writeAtomically(path, next.toString().encodeToByteArray())
                                    if (!result.isSuccess) fail("counter write failed: $result")
                                }
                            }
                        }
                    }
                }
            }
        }
        val raw: String = a.readBytes(path, 100).getOrNull()?.decodeToString() ?: fail("counter unreadable")
        val value: Int = raw.toIntOrNull() ?: fail("counter torn: '$raw'")
        // Each mutex serializes its own instance only; the final value is a whole number between one and the total.
        assertTrue(value in 1..workers * perWorker, "counter $value out of range")
        assertEquals(0, mutexA.activeKeyCount)
        assertEquals(0, mutexB.activeKeyCount)
        assertEquals(emptyList(), a.list(root).getOrNull().orEmpty().filter { FileNames.isTempName(it) })
    }
}
