package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The child process of [DiskFullRound9AdversarialTest]: its file size limit (`ulimit -f`) is a few kilobytes, so a big
 * journal write fails the way a full disk does (`EFBIG`). It reports what the journal did.
 */
internal object Round9DiskFullChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val outcomes = File(root, "outcomes")
        val partial = File(root, "partial")
        val journal = OutcomeJournal(AndroidOutcomeStorage(outcomes, partial), UploaderClock.System)
        val small = UploadResponse(200, mapOf("a" to "b"), ByteArray(64), false)
        val big = UploadResponse(200, mapOf("a" to "b"), ByteArray(400_000) { it.toByte() }, false)
        println("small=${journal.record("keep", small)}")
        val bigResult: Boolean = try {
            journal.record("big", big)
        } catch (e: Throwable) {
            println("bigThrew=${e::class.simpleName}")
            false
        }
        println("big=$bigResult")
        // A second big write over an existing entry: the old entry must survive a failed replacement.
        println("replace=${journal.record("keep", big)}")
        println("keepValid=${journal.inspect("keep") is JournalEntry.Valid}")
        println("keepBodySize=${(journal.inspect("keep") as? JournalEntry.Valid)?.response?.bodySize}")
        println("bigAbsent=${journal.inspect("big") == JournalEntry.Absent}")
        println("staged=${partial.list()?.size ?: 0}")
        println("after=${journal.record("after", small)}")
        System.out.flush()
    }
}

/**
 * Round 9 (A2), environment hostility, storage: a journal write that runs out of room (modelled by a child process with a
 * tiny file size limit, which fails a long write with `EFBIG` as a full disk does). The write reports `false`, no half
 * entry and no staging file stays, an entry already on disk survives a failed replacement, and the journal writes again.
 */
class DiskFullRound9AdversarialTest {

    private val root: File = Files.createTempDirectory("r9-full").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a write that runs out of room reports false, leaves nothing half, keeps the old entry and recovers`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val java = File(File(System.getProperty("java.home"), "bin"), "java").path
        val process: Process = ProcessBuilder(
            "/bin/sh", "-c", "ulimit -f 100 && exec \"\$1\" -cp \"\$2\" ${Round9DiskFullChild::class.java.name} \"\$3\"",
            "sh", java, System.getProperty("java.class.path"), root.path,
        ).redirectErrorStream(true).start()
        val finished: Boolean = process.waitFor(90, TimeUnit.SECONDS)
        val output: String = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "the child did not finish: $output")
        val lines: Map<String, String> = output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        // The limit may kill the child outright (a platform that does not ignore SIGXFSZ): then there is nothing to judge.
        assumeTrue("child output: $output", lines.containsKey("after"))
        assertEquals("true", lines["small"], output)
        assertEquals("false", lines["big"], "a write the disk refused was reported as journaled: $output")
        assertEquals(null, lines["bigThrew"], "the journal threw: $output")
        assertEquals("false", lines["replace"], output)
        assertEquals("true", lines["keepValid"], "the old entry did not survive a failed replacement: $output")
        assertEquals("64", lines["keepBodySize"], "the old entry changed: $output")
        assertEquals("true", lines["bigAbsent"], "a half entry stayed: $output")
        assertEquals("0", lines["staged"], "a staging file was left behind: $output")
        assertEquals("true", lines["after"], "the journal did not write again: $output")
    }
}
