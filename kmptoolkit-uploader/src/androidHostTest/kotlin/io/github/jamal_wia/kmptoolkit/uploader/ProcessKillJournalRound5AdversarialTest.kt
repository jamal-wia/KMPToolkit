package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A REAL process kill between the steps of a journaled settlement: a child JVM records an answer and settles it
 * through the production [OutcomeJournal] over the production [AndroidOutcomeStorage] on real files, and is
 * `Runtime.halt`ed (no `finally`, no hook, no flush beyond what the protocol itself synced) right after step k. The
 * parent plays the relaunched process: a new journal over the same directory sweeps what is left through a model
 * engine whose row is one atomic file. Earlier rounds killed the protocol in-process (a storage that starts to fail);
 * this one kills the process.
 *
 * Contract (OutcomeJournal KDoc): a stamped entry is deleted only after the commit returned, so a death at any step
 * leaves the entry or the applied commit, and the relaunch settles it exactly once; an unstamped one is deleted just
 * before the commit, so a death in that window uploads again (documented), and never commits twice.
 */
class ProcessKillJournalRound5AdversarialTest {

    private val base: File = Files.createTempDirectory("kmptoolkit-r5-kill").toFile()

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    private class Run(val exitCode: Int, val row: KillModelRow.State, val entryPresent: Boolean, val log: String)

    private fun entryPresent(root: File, itemId: String): Boolean = File(File(root, "outcomes"), itemId).isFile

    /** Spawns the child, waits for it, and reports what it left behind. */
    private fun runChild(root: File, itemId: String, stamp: Int?, status: Int, killAt: Int): Run {
        val row = KillModelRow(File(root, "row"))
        row.write(KillModelRow.State("INFLIGHT", 0, 0))
        val java = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val log = File(root, "child.log")
        val process: Process = ProcessBuilder(
            java.path,
            "-cp", System.getProperty("java.class.path"),
            Round5KillChild::class.java.name,
            root.path, itemId, stamp?.toString() ?: "none", status.toString(), killAt.toString(),
        ).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the child did not end within 60 s: ${log.readText().take(2000)}")
        }
        return Run(process.exitValue(), row.read(), entryPresent(root, itemId), log.readText().take(2000))
    }

    /** The relaunched process: sweeps the journal through the model engine. */
    private fun relaunch(root: File): KillModelRow.State {
        val row = KillModelRow(File(root, "row"))
        val journal = OutcomeJournal(AndroidOutcomeStorage(File(root, "outcomes"), File(root, "outcomes-partial")))
        runBlocking { withTimeout(15_000L) { journal.sweep(killModelSettle(row, KillSteps(Int.MAX_VALUE))) } }
        return row.read()
    }

    private fun scenario(stamp: Int?, status: Int, label: String, killedThrough: Int): List<Run> {
        val itemId = "kill-item"
        val runs = mutableListOf<Run>()
        // Step 0 is "before anything"; the loop runs past the last step, where the child must end by itself.
        for (killAt in 0..10) {
            val root: File = File(base, "$label-$killAt").also { it.mkdirs() }
            val ran: Run = runChild(root, itemId, stamp, status, killAt)
            assertTrue(
                ran.exitCode == KillSteps.KILLED_EXIT_CODE || ran.exitCode == 0,
                "$label kill at step $killAt: the child failed instead of being killed or finishing (exit ${ran.exitCode}): ${ran.log}",
            )
            // The kill must really land: a child that finishes at a step the protocol has would make the case vacuous.
            if (killAt <= killedThrough) {
                assertEquals(KillSteps.KILLED_EXIT_CODE, ran.exitCode, "$label: the child was not killed at step $killAt: ${ran.log}")
            } else {
                assertEquals(0, ran.exitCode, "$label: the child past the last step $killedThrough should finish (step $killAt): ${ran.log}")
            }
            runs += ran
        }
        return runs
    }

    /**
     * The premise of at-least-once: a process killed in the middle of the body never leaves the server holding a complete
     * request (the closing chunk is sent only when the body is whole), so a re-upload can never follow an accepted one.
     */
    @Test
    fun `a process killed in the middle of the body never leaves a complete request at the server`() {
        val size: Long = 48L * 1024 * 1024
        val source = File(base, "big.bin").also { java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(size) } }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val receivedBytes = java.util.concurrent.atomic.AtomicLong(0)
        val sawFinalChunk = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 30_000
                    val input = socket.getInputStream()
                    val buffer = ByteArray(64 * 1024)
                    var tail = ""
                    while (true) {
                        val read: Int = input.read(buffer)
                        if (read < 0) break
                        receivedBytes.addAndGet(read.toLong())
                        tail = (tail + String(buffer, maxOf(0, read - 16), minOf(read, 16), Charsets.ISO_8859_1)).takeLast(16)
                    }
                    sawFinalChunk.set(tail.endsWith("0\r\n\r\n"))
                }
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val java = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val log = File(base, "body-child.log")
            val process: Process = ProcessBuilder(
                java.path, "-cp", System.getProperty("java.class.path"), Round5KillBodyChild::class.java.name,
                "http://127.0.0.1:${server.localPort}/upload", source.path, "0.3",
            ).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("the body child did not end within 60 s: ${log.readText().take(2000)}")
            }
            assertEquals(KillSteps.KILLED_EXIT_CODE, process.exitValue(), "the child was not killed mid-body: ${log.readText().take(2000)}")
            reader.join(15_000)
            assertTrue(!reader.isAlive, "the server never saw the connection end")
            assertTrue(!sawFinalChunk.get(), "the server received the closing chunk of a body whose sender was killed")
            assertTrue(receivedBytes.get() in 1 until size, "the server saw ${receivedBytes.get()} of $size bytes")
        } finally {
            server.close()
        }
    }

    @Test
    fun `a stamped delivery killed at any step is committed exactly once by the relaunch and never lost in between`() {
        val runs: List<Run> = scenario(stamp = 0, status = 201, label = "stamped-delivery", killedThrough = 5)
        runs.forEachIndexed { killAt, ran ->
            // Between the death and the relaunch: the answer is protected, either by its entry or by its commit.
            if (killAt >= 1) {
                assertTrue(
                    ran.entryPresent || ran.row.commits == 1,
                    "killed after step $killAt the answer exists nowhere: entry gone and commits=${ran.row.commits}",
                )
            }
            assertTrue(ran.row.commits <= 1, "killed after step $killAt: commits=${ran.row.commits} before the relaunch")
            val root = File(base, "stamped-delivery-$killAt")
            val after: KillModelRow.State = relaunch(root)
            if (killAt >= 1) {
                assertEquals(KillModelRow.State("DONE", 0, 1), after, "killed after step $killAt, relaunch left $after")
            }
            assertTrue(!entryPresent(root, "kill-item"), "killed after step $killAt: the entry survives the relaunch for good")
        }
        // The last runs outlived every step: the child ended by itself.
        assertEquals(0, runs.last().exitCode, "the child was still being killed at step 10 (more steps than the protocol has?)")
    }

    @Test
    fun `a stamped failure killed at any step is charged exactly once by the relaunch`() {
        val runs: List<Run> = scenario(stamp = 0, status = 500, label = "stamped-failure", killedThrough = 5)
        runs.forEachIndexed { killAt, ran ->
            if (killAt >= 1) {
                assertTrue(
                    ran.entryPresent || ran.row.commits == 1,
                    "killed after step $killAt the failed answer exists nowhere: entry gone and commits=${ran.row.commits}",
                )
            }
            val root = File(base, "stamped-failure-$killAt")
            val after: KillModelRow.State = relaunch(root)
            if (killAt >= 1) {
                assertEquals(KillModelRow.State("PENDING", 1, 1), after, "killed after step $killAt, relaunch left $after")
            }
            assertTrue(!entryPresent(root, "kill-item"), "killed after step $killAt: the entry survives the relaunch for good")
        }
    }

    @Test
    fun `an unstamped answer killed at any step never commits twice and loses the answer only between its delete and its commit`() {
        for ((status, label) in listOf(201 to "unstamped-delivery", 500 to "unstamped-failure")) {
            val runs: List<Run> = scenario(stamp = null, status = status, label = label, killedThrough = 6)
            var lostWindows = 0
            runs.forEachIndexed { killAt, ran ->
                val root = File(base, "$label-$killAt")
                val lost: Boolean = killAt >= 1 && !ran.entryPresent && ran.row.commits == 0
                if (lost) lostWindows++
                val after: KillModelRow.State = relaunch(root)
                assertTrue(after.commits <= 1, "$label killed after step $killAt: committed ${after.commits} times")
                if (killAt >= 1 && !lost) {
                    assertEquals(1, after.commits, "$label killed after step $killAt: the answer was neither lost in the documented window nor committed ($after)")
                }
            }
            // The documented window is the one step between the delete and the commit's first write.
            // Exactly the two steps (the delete, and the commit's first write): proves the harness sees a window when
            // there is one, so the stamped cases above are not vacuous.
            assertEquals(2, lostWindows, "$label: the unprotected window spans $lostWindows steps")
        }
    }
}
