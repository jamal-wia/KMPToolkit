package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.ExistingWorkPolicy
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 4 (lifecycle chaos, resource starvation, contract lawyer) against the pieces that need no WorkManager: the
 * hand-off ledger as a protocol under real threads, the journal against files it cannot trust, and the multipart body
 * against empty parts. Every thread is joined with a bound, every loop is capped.
 */
class UploaderRound4ProtocolAdversarialTest {

    private val cleanup = mutableListOf<File>()
    private val servers = mutableListOf<ServerSocket>()

    @AfterTest
    fun cleanUp() {
        servers.forEach { runCatching { it.close() } }
        cleanup.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("uploader-r4-").toFile().also { cleanup += it }

    // --- the ledger as a protocol -----------------------------------------------------------------------------

    /**
     * A model of one item under WorkManager's unique-work rules, driven by the real ledger on real threads: launchers
     * write a claim (what `markInFlight` does) and then ask the ledger for a policy; jobs read the claim counter in
     * each serving iteration. KEEP drops a hand-off while the listed job is not yet reported finished (a delay after
     * the job left `doWork`), REPLACE lists a fresh job. At quiescence every claim must have been read by some
     * iteration, otherwise a hand-off was dropped and only the lease would have served it.
     */
    private class WorkManagerModel(private val itemId: String, private val ledger: UploadHandOffLedger, private val random: Random) {
        val issued = AtomicLong(0)
        val servedUpTo = AtomicLong(0)
        val keptDropped = AtomicInteger(0)
        val leftOpenWhileNotStopped = AtomicInteger(0)
        val replaced = AtomicInteger(0)
        val misplacedReplace = AtomicInteger(0)
        private val lock = Any()
        private var current: ModelJob? = null
        val threads = mutableListOf<Thread>()
        private val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private val ids = AtomicInteger(0)
        fun log(message: String) { events += "${System.nanoTime() / 1000 % 10_000_000}us $message" }
        fun trace(): String = events.joinToString("\n")

        private inner class ModelJob {
            @Volatile var reportedFinished = false
            @Volatile var cancelled = false
            @Volatile var pastServing = false
            val number: Int = ids.incrementAndGet()
        }

        private fun pause(maxSpins: Int) {
            repeat(random.nextInt(maxSpins + 1)) { Thread.yield() }
        }

        private fun startJob(job: ModelJob) {
            val worker: Thread = thread(isDaemon = true) {
                pause(40) // ENQUEUED to RUNNING
                log("job${job.number} jobStarted cancelled=${job.cancelled}")
                ledger.jobStarted(itemId)
                try {
                    var serveAgain = true
                    var iteration = 0
                    while (serveAgain) {
                        if (job.cancelled) {
                            // A queued job replaced before it ran anything is harmless (the replacement serves); a job
                            // cancelled between two serving iterations is the violation this counts.
                            if (iteration > 0 && !job.pastServing) misplacedReplace.incrementAndGet()
                            return@thread
                        }
                        iteration++
                        val seen: Long = ledger.handOffsSeen(itemId)
                        pause(20)
                        servedUpTo.accumulateAndGet(issued.get()) { a, b -> maxOf(a, b) }
                        pause(20)
                        val closed: UploadHandOffLedger.Finish = ledger.finishServing(itemId, seen, stopped = false)
                        log("job${job.number} finishServing(seen=$seen) -> $closed entry=${ledger.entry(itemId)}")
                        if (closed == UploadHandOffLedger.Finish.LEFT_OPEN) leftOpenWhileNotStopped.incrementAndGet()
                        serveAgain = closed == UploadHandOffLedger.Finish.MISSED
                        if (!serveAgain) job.pastServing = true
                    }
                    pause(30) // the lock release and the rest of doWork after finishServing
                } finally {
                    ledger.jobEnded(itemId)
                    log("job${job.number} jobEnded entry=${ledger.entry(itemId)}")
                }
                pause(60) // WorkManager has not yet marked the finished job
                synchronized(lock) { job.reportedFinished = true }
            }
            synchronized(threads) { threads += worker }
        }

        fun launch() {
            val claim: Long = issued.incrementAndGet()
            check(claim > 0)
            val policy: ExistingWorkPolicy = ledger.policyForHandOff(itemId)
            log("launch claim=$claim policy=$policy entry=${ledger.entry(itemId)}")
            synchronized(lock) {
                val listed: ModelJob? = current
                when (policy) {
                    ExistingWorkPolicy.KEEP ->
                        if (listed != null && !listed.reportedFinished) keptDropped.incrementAndGet() else startNew()
                    ExistingWorkPolicy.REPLACE -> {
                        replaced.incrementAndGet()
                        log("REPLACE cancels job${listed?.number} (pastServing=${listed?.pastServing}, reportedFinished=${listed?.reportedFinished})")
                        listed?.cancelled = true
                        startNew()
                    }
                    else -> error("unexpected policy $policy")
                }
            }
        }

        private fun startNew() {
            val job = ModelJob()
            current = job
            startJob(job)
        }
    }

    private class ModelStats(val rounds: Int, val violatingRounds: Int, val windowRounds: Int, val unexplained: Int, val misplaced: Int, val report: String)

    private fun runModel(launcherCount: Int, handOffsPerLauncher: Int, rounds: Int): ModelStats {
        var unexplained = 0
        var violatingRounds = 0
        var windowRounds = 0
        var misplaced = 0
        val report = StringBuilder()
        repeat(rounds) { round ->
            val ledger = UploadHandOffLedger()
            val model = WorkManagerModel("item-1", ledger, Random(round))
            val launchers: List<Thread> = (0 until launcherCount).map { launcherIndex ->
                thread(isDaemon = true) {
                    val pace = Random(round * 31 + launcherIndex)
                    repeat(handOffsPerLauncher) {
                        model.launch()
                        repeat(pace.nextInt(60)) { Thread.yield() }
                    }
                }
            }
            launchers.forEach { it.join(10_000) }
            assertTrue(launchers.none { it.isAlive }, "launchers hung in round $round")
            var guard = 0
            while (guard++ < 50) {
                val snapshot: List<Thread> = synchronized(model.threads) { model.threads.toList() }
                snapshot.forEach { it.join(10_000) }
                assertTrue(snapshot.none { it.isAlive }, "a job thread hung in round $round")
                if (synchronized(model.threads) { model.threads.size } == snapshot.size) break
            }
            val violated: Boolean = model.servedUpTo.get() < model.issued.get()
            if (model.leftOpenWhileNotStopped.get() > 0) windowRounds++
            if (violated) {
                violatingRounds++
                if (model.leftOpenWhileNotStopped.get() == 0) {
                    unexplained++
                    report.append("round $round: served ${model.servedUpTo.get()} of ${model.issued.get()}, kept-dropped ${model.keptDropped.get()}, replaced ${model.replaced.get()}, no LEFT_OPEN observed\n${model.trace()}\n")
                }
            }
            misplaced += model.misplacedReplace.get()
            val entry: UploadHandOffLedger.Entry? = ledger.entry("item-1")
            if (entry != null) assertEquals(0, entry.activeJobs, "round $round: an active job is left in the ledger")
        }
        return ModelStats(rounds, violatingRounds, windowRounds, unexplained, misplaced, report.toString())
    }

    @Test
    fun `with one launcher every hand-off is read by some iteration unless the documented replaced-job window ate it`() {
        val stats: ModelStats = runModel(launcherCount = 1, handOffsPerLauncher = 75, rounds = 300)
        println("ledger model, one launcher: ${stats.violatingRounds} of ${stats.rounds} rounds lost a hand-off, ${stats.windowRounds} rounds saw the two-active-jobs window, ${stats.unexplained} unexplained, ${stats.misplaced} REPLACE of a job still serving")
        assertEquals(0, stats.misplaced, "a REPLACE cancelled a job that was still serving")
        assertEquals(0, stats.unexplained, "hand-offs dropped with no replaced-job window to explain it:\n${stats.report}")
    }

    @Test
    fun `with three racing launchers the ledger never keeps an active job or hangs, and the losses are counted`() {
        val stats: ModelStats = runModel(launcherCount = 3, handOffsPerLauncher = 25, rounds = 150)
        println("ledger model, three launchers: ${stats.violatingRounds} of ${stats.rounds} rounds lost a hand-off, ${stats.windowRounds} rounds saw the two-active-jobs window, ${stats.unexplained} unexplained, ${stats.misplaced} REPLACE of a job between two serving iterations (the documented external-race residual)")
    }

    @Test
    fun `a hand-off in the replaced-job window is kept and leaves nothing that blocks the lease re-hand`() {
        val ledger = UploadHandOffLedger()
        // A finished serving; the engine's retry arrives and replaces it while A still unwinds.
        ledger.jobStarted("a")
        assertEquals(UploadHandOffLedger.Finish.FINISHED, ledger.finishServing("a", ledger.handOffsSeen("a"), false))
        assertEquals(ExistingWorkPolicy.REPLACE, ledger.policyForHandOff("a"))
        // B starts and closes before A's finally has run: two active jobs, so B leaves the item open.
        ledger.jobStarted("a")
        assertEquals(UploadHandOffLedger.Finish.LEFT_OPEN, ledger.finishServing("a", ledger.handOffsSeen("a"), false))
        // A third hand-off in that window is KEEP (WorkManager may still list B as running and drop it).
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
        ledger.jobEnded("a")
        ledger.jobEnded("a")
        // Nothing is left behind: the lease re-hand sees no entry and enqueues like a first hand-off.
        assertEquals(null, ledger.entry("a"))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
    }


    private fun journalOn(root: File): Pair<OutcomeJournal, AndroidOutcomeStorage> {
        val storage = AndroidOutcomeStorage(File(root, "outcomes"), File(root, "partial"))
        return OutcomeJournal(storage) to storage
    }


    @Test
    fun `a failed rename after a good write leaves no partial file and reports the outcome as unprotected`() {
        // path-hygiene-ignore: PH2 POSIX-only test body: it starts with TestHost.assumePosixFileSemantics, so Windows skips it
        TestHost.assumePosixFileSemantics("File.setWritable(false) / setReadable(false) change nothing on Windows (they set only the legacy read-only flag), so there is no unwritable or unreadable file to test")
        val root: File = tempDir()
        val (journal, _) = journalOn(root)
        File(root, "outcomes").mkdirs()
        File(root, "partial").mkdirs()
        val outcomes = File(root, "outcomes")
        // path-hygiene-ignore: PH2 POSIX-only test body: it starts with TestHost.assumePosixFileSemantics, so Windows skips it
        assertTrue(outcomes.setWritable(false), "could not make the directory read-only")
        try {
            val recorded: Boolean = journal.record("item-1", UploadResponse(201, emptyMap(), ByteArray(10), false))
            assertFalse(recorded, "a directory the rename cannot enter must not report the answer as journaled")
        } finally {
            // path-hygiene-ignore: PH2 restores the flags a POSIX-only test cleared; harmless on Windows, where it only touches the legacy read-only flag
            outcomes.setWritable(true)
        }
        assertEquals(emptyList(), File(root, "partial").listFiles()?.map { it.name }.orEmpty(), "partial file leaked")
        assertEquals(emptyList(), outcomes.listFiles()?.map { it.name }.orEmpty())
    }

    @Test
    fun `an unwritable leftovers directory reports the outcome as unprotected without throwing`() {
        // path-hygiene-ignore: PH2 POSIX-only test body: it starts with TestHost.assumePosixFileSemantics, so Windows skips it
        TestHost.assumePosixFileSemantics("File.setWritable(false) / setReadable(false) change nothing on Windows (they set only the legacy read-only flag), so there is no unwritable or unreadable file to test")
        val root: File = tempDir()
        val (journal, _) = journalOn(root)
        File(root, "outcomes").mkdirs()
        val partial = File(root, "partial").apply { mkdirs() }
        // path-hygiene-ignore: PH2 POSIX-only test body: it starts with TestHost.assumePosixFileSemantics, so Windows skips it
        assertTrue(partial.setWritable(false))
        try {
            assertFalse(journal.record("item-1", UploadResponse(201, emptyMap(), ByteArray(10), false)))
        } finally {
            // path-hygiene-ignore: PH2 restores the flags a POSIX-only test cleared; harmless on Windows, where it only touches the legacy read-only flag
            partial.setWritable(true)
        }
        assertEquals(null, File(root, "outcomes").listFiles()?.firstOrNull())
    }

    @Test
    fun `a sweep over hundreds of damaged entries and one good one settles the good one and deletes the damage`() {
        val root: File = tempDir()
        val (journal, _) = journalOn(root)
        assertTrue(journal.record("item-good", UploadResponse(201, emptyMap(), ByteArray(3), false), attemptStamp = 1))
        val dir = File(root, "outcomes")
        val random = Random(4)
        repeat(400) { index -> File(dir, "bad-$index").writeBytes(ByteArray(random.nextInt(64)) { random.nextInt(256).toByte() }) }
        val settled = mutableListOf<String>()

        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(20_000) {
                journal.sweep { id, _, _, _ ->
                    settled += id
                    JournalSettleOutcome.LANDED
                }
            }
        }

        assertEquals(listOf("item-good"), settled)
        assertEquals(emptyList(), dir.listFiles()?.map { it.name }.orEmpty())
    }

    // --- multipart framing ---------------------------------------------------------------------------------------

    private fun serveOnceCapturing(captured: java.util.concurrent.atomic.AtomicReference<ByteArray>): ServerSocket {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        servers += server
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 20_000
                val raw = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val input = it.getInputStream()
                while (true) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    raw.write(buffer, 0, read)
                    val tail: String = String(raw.toByteArray().takeLast(8).toByteArray(), Charsets.ISO_8859_1)
                    if (tail.endsWith("0\r\n\r\n")) break
                }
                captured.set(raw.toByteArray())
                it.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
        return server
    }

    /** The request's body with the chunked framing removed, read to the first zero-size chunk. */
    private fun dechunk(raw: ByteArray): Pair<String, String> {
        val text = String(raw, Charsets.ISO_8859_1)
        val headEnd: Int = text.indexOf("\r\n\r\n")
        val head: String = text.substring(0, headEnd)
        var position: Int = headEnd + 4
        val body = StringBuilder()
        while (true) {
            val lineEnd: Int = text.indexOf("\r\n", position)
            val size: Int = text.substring(position, lineEnd).trim().toInt(16)
            position = lineEnd + 2
            if (size == 0) break
            body.append(text, position, position + size)
            position += size + 2
        }
        return head to body.toString()
    }

    @Test
    fun `empty text values and empty file parts between full ones keep the body whole`() {
        val dir: File = tempDir()
        val empty = File(dir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val three = File(dir, "three.txt").apply { writeText("abc") }
        val captured = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val server: ServerSocket = serveOnceCapturing(captured)
        val request = UploadRequest(
            url = "http://127.0.0.1:${server.localPort}/u",
            fields = listOf(
                UploadField.Text("a", ""),
                UploadField.File("f", "e.bin", "application/octet-stream", empty.path),
                UploadField.Text("", "x"),
                UploadField.File("g", "z.txt", "text/plain", three.path),
                UploadField.Text("z", ""),
            ),
        )

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request, 5_000, 5_000)

        assertEquals(UploadResult.Completed(201), outcome.result)
        val (head, body) = dechunk(captured.get())
        val boundary: String = Regex("boundary=(\\S+)").find(head)!!.groupValues[1]
        val expected: String = "--$boundary\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"f\"; filename=\"e.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"\"\r\n\r\nx\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"g\"; filename=\"z.txt\"\r\nContent-Type: text/plain\r\n\r\nabc\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"z\"\r\n\r\n\r\n" +
            "--$boundary--\r\n"
        assertEquals(expected, body)
    }

    @Test
    fun `a file that grows while it is sent never reports more than whole progress`() {
        val dir: File = tempDir()
        val growing = File(dir, "grow.bin").apply { writeBytes(ByteArray(1000)) }
        val captured = java.util.concurrent.atomic.AtomicReference<ByteArray>()
        val server: ServerSocket = serveOnceCapturing(captured)
        val grown = AtomicBoolean(false)
        val fractions = mutableListOf<Float>()
        val request = UploadRequest(
            url = "http://127.0.0.1:${server.localPort}/u",
            fields = listOf(UploadField.Text("a", "b"), UploadField.File("f", "g.bin", "application/octet-stream", growing.path)),
        )

        val outcome: MultipartOutcome = performMultipartUploadCapturing(
            request, 5_000, 5_000,
            onWholePercent = { fraction ->
                synchronized(fractions) { fractions += fraction }
                // The first progress signal arrives while the file is being read: append to it now.
                if (grown.compareAndSet(false, true)) growing.appendBytes(ByteArray(200_000))
            },
        )

        assertEquals(UploadResult.Completed(201), outcome.result)
        synchronized(fractions) {
            assertTrue(fractions.all { it in 0f..1f }, "progress left 0..1: $fractions")
            assertTrue(fractions.zipWithNext().all { (a, b) -> b >= a }, "progress went backwards: $fractions")
        }
    }

    private fun answerWith(raw: String): ServerSocket {
        val server = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        servers += server
        thread(isDaemon = true) {
            val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                it.soTimeout = 20_000
                val input = it.getInputStream()
                val buffer = ByteArray(8192)
                var tail = ""
                while (!tail.endsWith("0\r\n\r\n")) {
                    val read: Int = runCatching { input.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(16)
                }
                it.getOutputStream().write(raw.encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
        return server
    }


    @Test
    fun `a redirect that sends a body keeps it or says it lost it`() {
        val server: ServerSocket = answerWith("HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 5\r\nConnection: close\r\n\r\nmoved")
        val request = UploadRequest(url = "http://127.0.0.1:${server.localPort}/u", fields = listOf(UploadField.Text("a", "b")))

        val outcome: MultipartOutcome = performMultipartUploadCapturing(request, 5_000, 5_000)

        val response: UploadResponse = outcome.response ?: fail("no response captured")
        assertTrue(String(response.body()) == "moved" || response.isBodyTruncated, "body ${String(response.body())}, truncated=${response.isBodyTruncated}")
    }
}
