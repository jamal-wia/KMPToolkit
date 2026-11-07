package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The outcome journal's files over many deliver / replay / expire cycles, a codec fed damaged and odd bytes, and
 * many threads replaying the same entries. Everything runs on a real directory with an injected clock, no engine.
 */
@RunWith(AndroidJUnit4::class)
class OutcomeJournalChurnAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var leftovers: File
    private val clock = AtomicInteger(0)
    private var nowMillis: Long = 1_000_000_000_000L

    @BeforeTest
    fun setUp() {
        root = File(context.cacheDir, "journal-churn-${System.nanoTime()}")
        dir = File(root, "outcomes")
        leftovers = File(root, "outcomes-partial")
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun storage(): AndroidOutcomeStorage = AndroidOutcomeStorage(dir, leftovers, now = { nowMillis })
    private fun journal(): OutcomeJournal = OutcomeJournal(storage(), clock = { nowMillis })

    private fun response(body: ByteArray = """{"a":1}""".encodeToByteArray(), status: Int = 201): UploadResponse =
        UploadResponse(status, mapOf("location" to "/x", "x-n" to "1"), body, false)

    private fun fileCount(): Int = root.walkTopDown().count { it.isFile }

    @Test
    fun `two hundred deliver, replay and expire cycles leave no file behind`() = runBlocking {
        val journal: OutcomeJournal = journal()
        repeat(200) { index ->
            val id = "item-$index"
            when (index % 4) {
                0 -> { // delivered through the transport's own settle
                    assertTrue(journal.record(id, response()))
                    assertTrue(journal.settleRecorded(id, response()) { _, _, onSettling -> onSettling(); true })
                }
                1 -> { // replayed by a later process
                    assertTrue(journal.record(id, response()))
                    assertEquals(ReplayOutcome.Replayed, journal.replay(id) { _, _, onSettling -> onSettling(); true })
                }
                2 -> { // expired after a week
                    assertTrue(journal.record(id, response()))
                    nowMillis += 8.days.inWholeMilliseconds
                    assertEquals(ReplayOutcome.Discarded, journal.replay(id) { _, _, _ -> error("an expired entry must not settle") })
                }
                else -> { // an engine that does not know the item: settles nothing, the entry still goes
                    assertTrue(journal.record(id, response()))
                    assertEquals(ReplayOutcome.Replayed, journal.replay(id) { _, _, _ -> true })
                }
            }
            // a cut-short write of the dead process, ignored by readers and swept later
            File(leftovers.apply { mkdirs() }, "$id.${index}").apply {
                writeBytes(ByteArray(index % 50))
                setLastModified(nowMillis - 2 * 60 * 60 * 1000L)
            }
            nowMillis += 2 * 60 * 60 * 1000L
            if (index % 10 == 9) journal.sweep { _, _, _ -> true }
        }
        journal.sweep { _, _, _ -> true }

        assertEquals(0, fileCount(), "files left: ${root.walkTopDown().filter { it.isFile }.map { it.name }.toList()}")
    }

    @Test
    fun `a replay whose settlement fails after onSettling puts the entry back with its original time and delivers it once later`() = runBlocking {
        val journal: OutcomeJournal = journal()
        val writtenAt: Long = nowMillis
        assertTrue(journal.record("item-1", response()))
        nowMillis += 3.days.inWholeMilliseconds

        val first: ReplayOutcome = journal.replay("item-1") { _, _, onSettling -> onSettling(); false }

        assertEquals(ReplayOutcome.Kept, first)
        val back: JournalEntry = journal.inspect("item-1")
        assertIs<JournalEntry.Valid>(back, "the answer the failed commit did not take is back on disk")
        assertEquals(writtenAt, back.writtenAtMillis, "the 7-day expiry keeps counting from the first write")
        assertEquals(201, back.response.statusCode)
        var delivered = 0
        val second: ReplayOutcome = journal.replay("item-1") { _, _, onSettling -> onSettling(); delivered++; true }
        assertEquals(ReplayOutcome.Replayed, second)
        assertEquals(1, delivered)
        assertEquals(0, fileCount())
    }

    @Test
    fun `every prefix of a valid entry and a few thousand mutations of it read as damaged without throwing`() {
        val valid: ByteArray = OutcomeCodec.encode(
            UploadResponse(200, mapOf("a" to "b", "location" to "/jobs/1"), ByteArray(300) { it.toByte() }, true),
            nowMillis,
        )
        for (length in valid.indices) {
            val decoded: DecodedOutcome = OutcomeCodec.decode(valid.copyOf(length))
            assertTrue(decoded !is DecodedOutcome.Ok, "a prefix of $length bytes must not read as a whole entry")
        }
        val random = Random(20260607)
        repeat(5_000) {
            val mutated: ByteArray = valid.copyOf()
            repeat(1 + random.nextInt(4)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            val decoded: DecodedOutcome = OutcomeCodec.decode(mutated)
            if (decoded is DecodedOutcome.Ok) assertTrue(decoded.response.body().size <= MAX_RESPONSE_BODY_BYTES)
        }
        // lengths that overflow or claim far more than the file holds
        listOf(Int.MAX_VALUE, Int.MIN_VALUE, -1, 0x7fffff00, 1 shl 30).forEach { claimed ->
            val bytes: ByteArray = valid.copyOf()
            for (offset in 17..bytes.size - 4 step 3) {
                val damaged: ByteArray = bytes.copyOf()
                for (i in 0..3) damaged[offset + i] = (claimed ushr (24 - 8 * i)).toByte()
                OutcomeCodec.decode(damaged)
            }
        }
    }

    @Test
    fun `odd responses survive a round trip through the file`() = runBlocking {
        val journal: OutcomeJournal = journal()
        val headers: Map<String, String> = buildMap {
            repeat(200) { put("x-h-$it", "värde-$it-日本語-😀") }
        }
        val cases: List<UploadResponse> = listOf(
            UploadResponse(0, emptyMap(), ByteArray(0), false),
            UploadResponse(-1, headers, ByteArray(MAX_RESPONSE_BODY_BYTES) { (it * 31).toByte() }, true),
            UploadResponse(Int.MAX_VALUE, mapOf("empty" to ""), ByteArray(1) { 0 }, false),
        )
        cases.forEachIndexed { index, original ->
            val id = "odd-$index"
            assertTrue(journal.record(id, original, MAX_RESPONSE_BODY_BYTES))
            val entry: JournalEntry = journal.inspect(id)
            assertIs<JournalEntry.Valid>(entry)
            assertEquals(original.enforced(MAX_RESPONSE_BODY_BYTES), entry.response, "case $index")
        }
    }

    @Test
    fun `eight threads replaying the same two hundred entries settle each exactly once`() {
        val journal: OutcomeJournal = journal()
        repeat(200) { assertTrue(journal.record("item-$it", response())) }
        val settled = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val threads: List<Thread> = List(8) { offset ->
            thread {
                runCatching {
                    runBlocking {
                        withTimeout(60_000L) {
                            repeat(200) { step ->
                                val id = "item-${(step + offset * 25) % 200}"
                                journal.replay(id) { itemId, _, onSettling ->
                                    settled.getOrPut(itemId) { AtomicInteger() }.incrementAndGet()
                                    onSettling()
                                    true
                                }
                            }
                        }
                    }
                }.onFailure { failures += it }
            }
        }
        threads.forEach { it.join(90_000) }

        assertTrue(failures.isEmpty(), "failures: $failures")
        assertEquals(200, settled.size)
        assertTrue(settled.values.all { it.get() == 1 }, "settled more than once: ${settled.filterValues { it.get() != 1 }.keys}")
        assertEquals(0, fileCount())
    }

    @Test
    fun `a replay racing the body cut and a delete for the same entry settles at most once and leaves nothing`() {
        val journal: OutcomeJournal = journal()
        val settledTotal = AtomicInteger(0)
        repeat(100) { round ->
            val id = "race-$round"
            assertTrue(journal.record(id, response(body = ByteArray(5_000))))
            val threads: List<Thread> = listOf(
                thread { runBlocking { withTimeout(20_000L) { journal.replay(id) { _, _, onSettling -> settledTotal.incrementAndGet(); onSettling(); true } } } },
                thread { runBlocking { withTimeout(20_000L) { journal.restrictBody(id, 10) } } },
                thread { if (round % 2 == 0) journal.delete(id) },
            )
            threads.forEach { it.join(30_000) }
        }
        assertTrue(settledTotal.get() <= 100, "a response was settled more than once: ${settledTotal.get()}")
        assertEquals(0, fileCount(), "an entry was written back after it had been handled")
    }

    @Test
    fun `a directory where an entry should be, and a sweep over it, neither throw nor loop`() = runBlocking {
        dir.mkdirs()
        File(dir, "item-dir").mkdirs()
        File(dir, "item-empty").writeBytes(ByteArray(0))
        File(dir, "item-junk").writeBytes(ByteArray(4096) { 7 })
        val journal: OutcomeJournal = journal()

        withTimeout(10_000L) { journal.sweep { _, _, _ -> error("nothing here is a usable entry") } }

        assertTrue(File(dir, "item-junk").exists().not(), "unusable entries are deleted")
        assertTrue(File(dir, "item-empty").exists().not())
    }

    @Test
    fun `a hundred replays cancelled while waiting for the item's lock do not wedge the next replay`() = runBlocking {
        val journal: OutcomeJournal = journal()
        val pool = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        assertTrue(journal.record("held", response()))
        val holding = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = pool.launch {
            journal.replay("held") { _, _, onSettling -> holding.complete(Unit); release.await(); onSettling(); true }
        }
        withTimeout(10_000L) { holding.await() }
        val waiters: List<kotlinx.coroutines.Job> = List(100) {
            pool.launch {
                journal.replay("held") { _, _, _ -> error("a waiter must find the entry gone") }
            }
        }
        kotlinx.coroutines.delay(200L)
        waiters.forEach { it.cancel() }
        withTimeout(10_000L) { waiters.forEach { it.join() } }
        release.complete(Unit)
        withTimeout(10_000L) { holder.join() }

        assertTrue(journal.record("held", response()))
        val outcome: ReplayOutcome = withTimeout(10_000L) { journal.replay("held") { _, _, onSettling -> onSettling(); true } }

        pool.cancel()
        assertEquals(ReplayOutcome.Replayed, outcome)
        assertEquals(0, fileCount())
    }

    @Test
    fun `an entries directory that is a plain file makes a record fail quietly and a sweep do nothing`() = runBlocking {
        root.mkdirs()
        File(root, "outcomes").writeText("not a directory")
        val journal: OutcomeJournal = journal()

        assertEquals(false, journal.record("item-1", response()))
        withTimeout(10_000L) { journal.sweep { _, _, _ -> error("nothing to settle") } }
        assertEquals(JournalEntry.Absent, journal.inspect("item-1"))
    }

    @Test
    fun `a response with thousands of headers and the largest body is bounded on disk and reads back whole`() = runBlocking {
        val journal: OutcomeJournal = journal()
        val headers: Map<String, String> = buildMap { repeat(20_000) { put("x-$it", "v".repeat(40)) } }
        val original = UploadResponse(200, headers, ByteArray(MAX_RESPONSE_BODY_BYTES) { it.toByte() }, false)

        assertTrue(journal.record("big", original))

        val onDisk: Long = File(dir, "big").length()
        assertTrue(onDisk < 3L * 1024 * 1024, "entry of $onDisk bytes for a 1 MiB body")
        assertIs<JournalEntry.Valid>(journal.inspect("big"))
        assertEquals(1, fileCount())
    }
}
