package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Round 9 (A2), environment hostility, storage: the Android outcome journal on a real file system that is changed under
 * it: its directories deleted or replaced by files, an entry replaced by a directory or a link, entries cut or garbled
 * by another process, a file nobody may read, names of every length and script, and a storm of writers and readers
 * while a third party keeps deleting the whole tree. The journal never throws for a storage problem, an entry is whole
 * or absent, and it recovers as soon as the environment does.
 */
class StorageEnvironmentRound9AdversarialTest {

    private val root: File = Files.createTempDirectory("r9-env").toFile()
    private val outcomes = File(root, "outcomes")
    private val partial = File(root, "partial")
    private val journal = OutcomeJournal(AndroidOutcomeStorage(outcomes, partial), UploaderClock.System)
    private val response = UploadResponse(200, mapOf("a" to "b"), ByteArray(512) { it.toByte() }, false)

    @AfterTest
    fun cleanUp() {
        root.walkTopDown().forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `both directories deleted between writes are created again and nothing is lost that was written after`() {
        for (round in 1..20) {
            assertTrue(journal.record("a$round", response), "round $round: write after the tree vanished")
            assertIs<JournalEntry.Valid>(journal.inspect("a$round"))
            partial.deleteRecursively()
            assertTrue(journal.record("b$round", response), "round $round: write after the staging directory vanished")
            outcomes.deleteRecursively()
            assertEquals(JournalEntry.Absent, journal.inspect("a$round"))
            assertEquals(JournalEntry.Absent, journal.inspect("b$round"))
        }
        assertEquals(emptyList(), partial.list()?.toList().orEmpty())
    }

    @Test
    fun `an entry directory replaced by a regular file never throws and the journal works again once it is removed`() {
        assertTrue(journal.record("seed", response))
        outcomes.deleteRecursively()
        outcomes.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(journal.record("x", response))
        assertEquals(JournalEntry.Absent, journal.inspect("seed"))
        journal.delete("seed")
        runBlocking { journal.sweep { _, _, _, _ -> JournalSettleOutcome.LANDED } }
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "a refused write left a staging file")
        assertTrue(outcomes.delete())
        assertTrue(journal.record("x", response))
        assertIs<JournalEntry.Valid>(journal.inspect("x"))
    }

    @Test
    fun `a staging directory replaced by a regular file fails the write, keeps the entries and recovers`() {
        assertTrue(journal.record("seed", response))
        partial.deleteRecursively()
        partial.writeBytes(byteArrayOf(9))
        assertFalse(journal.record("x", response))
        assertIs<JournalEntry.Valid>(journal.inspect("seed"), "an entry was lost to a broken staging directory")
        runBlocking { journal.sweep { _, _, _, _ -> JournalSettleOutcome.LANDED } }
        assertTrue(partial.delete())
        assertTrue(journal.record("x", response))
        assertIs<JournalEntry.Valid>(journal.inspect("x"))
    }

    @Test
    fun `an entry replaced by a non-empty directory reads as absent, refuses a write, and leaves the others alone`() {
        assertTrue(journal.record("other", response))
        val squatter = File(outcomes, "x").apply { mkdirs() }
        File(squatter, "inner").writeBytes(byteArrayOf(1))
        assertEquals(JournalEntry.Absent, journal.inspect("x"))
        assertFalse(journal.record("x", response), "a write cannot replace a directory")
        journal.delete("x")
        assertEquals(emptyList(), partial.list()?.toList().orEmpty())
        val replayed: MutableList<String> = mutableListOf()
        runBlocking { journal.sweep { id, _, _, _ -> replayed += id; JournalSettleOutcome.LANDED } }
        assertEquals(listOf("other"), replayed, "the sweep over a squatted name replayed the wrong entries")
        assertEquals(JournalEntry.Absent, journal.inspect("other"), "a replayed entry is deleted once it landed")
    }

    @Test
    fun `links are followed for a read, a dangling link is absent, and deleting a link never deletes its target`() {
        assertTrue(journal.record("real", response))
        val real = File(outcomes, "real")
        val linkOk: File = File(outcomes, "link-ok")
        val linkDangling: File = File(outcomes, "link-dangling")
        try {
            Files.createSymbolicLink(linkOk.toPath(), real.toPath())
            Files.createSymbolicLink(linkDangling.toPath(), File(root, "nowhere").toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false)
        }
        assertIs<JournalEntry.Valid>(journal.inspect("link-ok"))
        assertEquals(JournalEntry.Absent, journal.inspect("link-dangling"))
        // A link to a file far above the entry bound: not read, deleted as damaged; the target is not the journal's.
        val huge = File(root, "huge").apply { RandomAccessFile(this, "rw").use { it.setLength(64L * 1024 * 1024) } }
        val linkHuge = File(outcomes, "link-huge")
        Files.createSymbolicLink(linkHuge.toPath(), huge.toPath())
        assertEquals(JournalEntry.Discarded, journal.inspect("link-huge"))
        assertFalse(Files.isSymbolicLink(linkHuge.toPath()), "the damaged link was kept")
        assertTrue(huge.exists() && huge.length() == 64L * 1024 * 1024, "the journal deleted a file it does not own through a link")
        assertIs<JournalEntry.Valid>(journal.inspect("real"))
    }

    @Test
    fun `entries cut, emptied or garbled by another process read as discarded and are removed, then the id is writable`() {
        val sizes: List<Int> = listOf(0, 1, 3, 4, 9, 64, 4_096, 1_048_576 + 4_096)
        for (size in sizes) {
            assertTrue(journal.record("id$size", response))
            File(outcomes, "id$size").writeBytes(ByteArray(size) { (it * 31 + 7).toByte() })
            val entry: JournalEntry = journal.inspect("id$size")
            assertEquals(JournalEntry.Discarded, entry, "a $size byte garbage entry")
            assertFalse(File(outcomes, "id$size").exists(), "a $size byte garbage entry was kept")
            assertTrue(journal.record("id$size", response))
            assertIs<JournalEntry.Valid>(journal.inspect("id$size"))
        }
        // Cut at every length by a process that died mid-copy.
        assertTrue(journal.record("cut", response))
        val whole: ByteArray = File(outcomes, "cut").readBytes()
        for (length in 0 until whole.size step 7) {
            File(outcomes, "cut").writeBytes(whole.copyOf(length))
            assertEquals(JournalEntry.Discarded, journal.inspect("cut"), "an entry cut at $length of ${whole.size} bytes")
        }
        assertEquals(JournalEntry.Absent, journal.inspect("cut"))
    }

    @Test
    fun `an entry nobody may read is unreadable and kept, and is read once the permission is back`() {
        assertTrue(journal.record("locked", response))
        val file = File(outcomes, "locked")
        assumeTrue(file.setReadable(false) && !file.canRead())
        assertEquals(JournalEntry.Unreadable, journal.inspect("locked"))
        assertTrue(file.exists(), "an unreadable entry was deleted: nothing is known about it")
        assertEquals(JournalEntry.Unreadable, journal.inspect("locked"))
        file.setReadable(true)
        assertIs<JournalEntry.Valid>(journal.inspect("locked"))
    }

    @Test
    fun `a name of any length or script is journaled whole or reported not journaled, never half and never thrown`() {
        val scripts: Map<String, String> = mapOf("ascii" to "a", "cyrillic" to "ж", "cjk" to "漢", "emoji" to "😀")
        for ((script: String, unit: String) in scripts) {
            for (length in listOf(1, 50, 99, 100, 109, 110, 120, 150, 199, 200)) {
                val id: String = unit.repeat(if (script == "emoji") length / 2 + 1 else length).take(length)
                val recorded: Boolean = journal.record(id, response)
                if (recorded) {
                    assertIs<JournalEntry.Valid>(journal.inspect(id), "$script id of $length reported journaled but reads back differently")
                } else {
                    assertEquals(JournalEntry.Absent, journal.inspect(id), "$script id of $length reported not journaled but an entry exists")
                }
                assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "$script id of $length left a staging file")
                journal.delete(id)
            }
        }
    }

    @Test
    fun `writers and readers while a third party keeps deleting the tree see only whole entries or none`() {
        val stop = AtomicBoolean(false)
        val thrown: AtomicInteger = AtomicInteger()
        val discarded: AtomicInteger = AtomicInteger()
        val recordedTrue: AtomicInteger = AtomicInteger()
        val errors: MutableList<Throwable> = java.util.Collections.synchronizedList(mutableListOf())
        val workers: List<Thread> = (0 until 4).map { w ->
            thread(name = "r9-writer-$w") {
                var n = 0
                try {
                    while (!stop.get()) {
                        val id = "w$w-${n++ % 25}"
                        try {
                            if (journal.record(id, response)) recordedTrue.incrementAndGet()
                            val entry: JournalEntry = journal.inspect(id)
                            if (entry == JournalEntry.Discarded) discarded.incrementAndGet()
                            if (n % 3 == 0) journal.delete(id)
                        } catch (e: Throwable) {
                            thrown.incrementAndGet(); errors += e
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        val vandal: Thread = thread(name = "r9-vandal") {
            var n = 0
            while (!stop.get()) {
                if (n++ % 2 == 0) outcomes.deleteRecursively() else partial.deleteRecursively()
                Thread.sleep(2)
            }
        }
        Thread.sleep(3_000)
        stop.set(true)
        (workers + vandal).forEach { it.join(10_000) }
        assertTrue((workers + vandal).none { it.isAlive }, "a thread did not finish")
        assertEquals(0, thrown.get(), "the journal threw under a vanishing tree: ${errors.firstOrNull()}")
        assertEquals(0, discarded.get(), "a half-written entry was visible to a reader")
        assertTrue(recordedTrue.get() > 0, "no write succeeded at all")
        // Quiet again: every surviving entry is whole and nothing is staged.
        for (id in outcomes.list()?.toList().orEmpty()) assertIs<JournalEntry.Valid>(journal.inspect(id), "$id is not whole after the storm")
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "staging files were left behind")
    }
}
