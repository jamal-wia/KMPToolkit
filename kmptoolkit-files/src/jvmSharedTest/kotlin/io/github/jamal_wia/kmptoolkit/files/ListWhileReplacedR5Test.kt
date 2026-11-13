package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 5 of the breaker for unit G, Q3 on the macOS JVM (and the Android host runtime): files replaced while a directory
 * is listed or read. Contract (docs 04): a replace is atomic, so a name that is replaced (by `writeAtomically` or by a
 * replacing `move`) never disappears from a listing taken meanwhile, `list` is sorted without duplicates, and a reader
 * sees the whole old or the whole new content. Threads meet at a barrier; the readers stop on a flag the writer sets;
 * every wait is bounded and fails with its own message.
 */
class ListWhileReplacedR5Test {
    private val files: FileAccess = createFileAccess()
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("kmptoolkit-listwhile-r5").toRealPath().toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a name that is replaced while the directory is listed and read never vanishes and never tears`() {
        val names: List<String> = (0 until 100).map { "n" + it.toString().padStart(3, '0') }
        fun content(round: Int, name: String): ByteArray = (name + "#" + round + ";").repeat(200).encodeToByteArray()
        for (name in names) assertTrue(files.writeAtomically(File(directory, name).path, content(0, name)).isSuccess)
        val rounds = 40
        val done = AtomicBoolean(false)
        val barrier = CyclicBarrier(4)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writer = pool.submit(Callable {
                barrier.await(60, TimeUnit.SECONDS)
                try {
                    for (round in 1..rounds) {
                        for (name in names) {
                            val target: String = File(directory, name).path
                            if (round % 2 == 0) {
                                val result: FileResult<Unit> = files.writeAtomically(target, content(round, name), WriteOptions(durability = Durability.Buffered))
                                check(result.isSuccess) { "round $round $name: $result" }
                            } else {
                                // A replacing move of a staged file onto the name.
                                val staged: String = File(directory, "staged-$name").path
                                check(files.writeAtomically(staged, content(round, name), WriteOptions(durability = Durability.Buffered)).isSuccess)
                                val result: FileResult<Unit> = files.move(staged, target)
                                check(result.isSuccess) { "round $round $name: $result" }
                            }
                        }
                    }
                } finally {
                    done.set(true)
                }
            })
            val listers = (1..2).map {
                pool.submit(Callable {
                    barrier.await(60, TimeUnit.SECONDS)
                    var lists = 0
                    do {
                        val finished: Boolean = done.get()
                        val listed: List<String> = files.list(directory.path).getOrNull() ?: error("list failed")
                        lists++
                        check(listed == listed.sorted() && listed.toSet().size == listed.size) { "not sorted or duplicated: $listed" }
                        val missing: List<String> = names.filter { it !in listed }
                        check(missing.isEmpty()) { "a replaced name vanished from a listing: $missing" }
                        val strange: List<String> = listed.filter { it !in names && !it.startsWith("staged-") && !FileNames.isTempName(it) }
                        check(strange.isEmpty()) { "unexpected entries: $strange" }
                    } while (!finished)
                    lists
                })
            }
            val reader = pool.submit(Callable {
                barrier.await(60, TimeUnit.SECONDS)
                var reads = 0
                do {
                    val finished: Boolean = done.get()
                    for (name in names) {
                        val result: FileResult<ByteArray> = files.readBytes(File(directory, name).path, 1 shl 20)
                        val bytes: ByteArray = result.getOrNull() ?: error("read of $name failed: $result")
                        val text: String = bytes.decodeToString()
                        val round: Int = text.substringAfter('#').substringBefore(';').toInt()
                        check(text == content(round, name).decodeToString()) { "torn read of $name (round $round, ${bytes.size} bytes)" }
                        reads++
                    }
                } while (!finished)
                reads
            })
            try {
                writer.get(120, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                error("HANG: the writer did not finish within 120 s")
            }
            for (lister in listers) assertTrue(lister.get(60, TimeUnit.SECONDS) >= 1)
            assertTrue(reader.get(60, TimeUnit.SECONDS) >= names.size)
        } finally {
            done.set(true)
            pool.shutdownNow()
        }
        for (name in names) assertEquals(content(rounds, name).decodeToString(), File(directory, name).readText(), "final content of $name")
        assertEquals(names, files.list(directory.path).getOrNull(), "litter after the storm")
    }
}
