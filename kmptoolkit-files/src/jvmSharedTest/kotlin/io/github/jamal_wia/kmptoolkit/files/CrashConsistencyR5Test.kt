package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 5 of the breaker for unit G, Q1 (crash consistency). A child JVM runs one `FileAccess` call over the real java.nio
 * operations through a stand-in that counts every low-level call and halts the process (no `finally`, no hook) at the
 * Nth call, or after half of the Nth call when that call is a write. The kill point is a call number from a dry run,
 * never a time. After every kill the parent checks the documented contract of docs/04 `writeAtomically` and `move`:
 * the destination holds the whole old or the whole new content (never a mix, never empty in between), a move leaves
 * the bytes under at least one name, nothing is left beside the entries except at most one temporary file named by
 * `FileNames.isTempName` ("A crash can leave a temporary file; `sweep` it"), `sweep` removes exactly that leftover,
 * and a following call recovers.
 */
class CrashConsistencyR5Test {
    private lateinit var base: File
    private val files: FileAccess = createFileAccess()

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("kmptoolkit-crash-r5").toRealPath().toFile()
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    private class Run(val exitCode: Int, val log: String)

    private fun runChild(root: File, scenario: String, killAt: Int, mode: KillModeR5): Run {
        val java = File(File(System.getProperty("java.home"), "bin"), if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val log = File(root.parentFile, root.name + ".child.log")
        val process: Process = ProcessBuilder(
            java.path,
            "-cp", System.getProperty("java.class.path"),
            CrashChildR5::class.java.name,
            root.path, scenario, killAt.toString(), mode.name,
        ).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("HANG: the child of $scenario at call $killAt ($mode) did not end within 90 s: ${log.readText().take(2000)}")
        }
        return Run(process.exitValue(), log.readText().take(2000))
    }

    private fun tempNames(directory: File): List<String> =
        directory.list()?.filter { FileNames.isTempName(it) }.orEmpty()

    /** Judges the state a kill left in [root] for [scenario]; [what] names the kill point for the failure message. */
    private fun judge(scenario: ScenarioR5, root: File, what: String) {
        val l = ScenarioLayoutR5(root)
        val destination: File = if (scenario.name == "create-with-parents-durable" || scenario.name == "move-into-new-parent") l.nested else l.destination
        val content: ByteArray? = if (destination.isFile) destination.readBytes() else null
        // 1. No mix: the destination is absent (only where it did not exist), the whole old content, or the whole new one.
        val isOld: Boolean = content != null && content.contentEquals(l.oldBytes)
        val isNew: Boolean = content != null && content.contentEquals(l.newBytes)
        if (content != null) {
            assertTrue(isOld || isNew, "$what: the destination holds neither the whole old nor the whole new content (${content.size} bytes)")
        } else {
            assertTrue(!scenario.destinationExistedBefore, "$what: the destination that existed before the call is gone")
            assertTrue(!destination.exists(), "$what: the destination exists but is not a regular file")
        }
        if (isOld) assertTrue(scenario.destinationExistedBefore, "$what: the destination holds old bytes that never existed there")
        // 2. A move never loses the bytes: the source is whole, or the destination holds the new bytes.
        if (scenario.isMove) {
            val sourceHolds: Boolean = l.source.isFile && l.source.readBytes().contentEquals(l.newBytes)
            assertTrue(!l.source.exists() || sourceHolds, "$what: the source is torn")
            assertTrue(sourceHolds || isNew, "$what: the move left the bytes under no name (source gone, destination not the new content)")
            if (isOld) assertTrue(sourceHolds, "$what: the destination is old and the source is gone")
        }
        // 3. At most one temporary file, and only where the call may create one; nothing else beside the entries.
        val tempDirectory: File = scenario.tempDir(l)
        val temps: List<String> = tempNames(tempDirectory)
        assertTrue(temps.size <= 1, "$what: more than one temporary file left: $temps")
        val listed: List<String> = destination.parentFile.list()?.toList().orEmpty()
        val stray: List<String> = listed.filter { it != destination.name && it != l.source.name && !FileNames.isTempName(it) }
        assertTrue(stray.isEmpty(), "$what: unexpected entries beside the destination: $stray")
        // 4. A temporary file is recognised by name and removed by sweep once it is old enough.
        for (name in temps) {
            assertTrue(File(tempDirectory, name).setLastModified(1_000L), "$what: cannot date the leftover")
        }
        val swept: FileResult<Int> = files.sweep(tempDirectory.path, olderThanMillis = 3_600_000L) { FileNames.isTempName(it) }
        assertEquals(FileResult.Success(temps.size), swept, "$what: sweep did not remove exactly the leftover $temps")
        assertEquals(emptyList(), tempNames(tempDirectory), "$what: a temporary file survived the sweep")
        // 5. A following call recovers: the same call ends in a typed result and the final state is the documented one.
        val again: FileResult<Unit> = scenario.run(files, l)
        when (again) {
            is FileResult.Success -> {
                assertTrue(destination.readBytes().contentEquals(l.newBytes), "$what: the repeated call succeeded but the destination is not the new content")
                if (scenario.isMove) assertTrue(!l.source.exists(), "$what: the repeated move succeeded but the source is still there")
            }
            is FileResult.Failure -> {
                val error: FileError = again.error
                val allowed: Boolean = when {
                    // The bytes are already in place: a no-replace call answers AlreadyExists, a move of a consumed source NotFound.
                    error is FileError.AlreadyExists -> isNew
                    error is FileError.NotFound -> scenario.isMove && isNew && !l.source.exists()
                    else -> false
                }
                assertTrue(allowed, "$what: the repeated call failed with $error (state: old=$isOld new=$isNew, source=${l.source.exists()})")
            }
        }
        assertTrue(destination.readBytes().contentEquals(l.newBytes), "$what: after the repeated call the destination is not the new content")
        assertEquals(emptyList(), tempNames(tempDirectory), "$what: the repeated call left a temporary file")
    }

    private fun prepare(scenario: ScenarioR5, label: String): File {
        val root: File = File(base, label).also { it.mkdirs() }
        scenario.setup(ScenarioLayoutR5(root))
        return root
    }

    @Test
    fun `every kill point of every write and move leaves a whole old or new destination and a recoverable directory`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val failures: MutableList<String> = mutableListOf()
            var killPoints = 0
            for (scenario in ScenariosR5.all) {
                // Dry run: the child finishes by itself and reports the operations it ran.
                val dryRoot: File = prepare(scenario, "${scenario.name}-dry")
                val dry: Run = runChild(dryRoot, scenario.name, 0, KillModeR5.Before)
                assertEquals(0, dry.exitCode, "${scenario.name}: the dry run failed: ${dry.log}")
                val ops: List<String> = File(dryRoot, "ops.txt").readText().split('\n')
                assertTrue(ops.size >= 3, "${scenario.name}: a dry run with fewer than three operations is not a real test: $ops")
                judge(scenario, dryRoot, "${scenario.name} (no kill)")
                val tasks: MutableList<Pair<String, Callable<Unit>>> = mutableListOf()
                for (k in 1..ops.size) {
                    val modes: List<KillModeR5> = if (ops[k - 1] == "write") listOf(KillModeR5.Before, KillModeR5.HalfWrite) else listOf(KillModeR5.Before)
                    for (mode in modes) {
                        val label = "${scenario.name}-k$k-${mode.name}"
                        killPoints++
                        tasks += label to Callable {
                            val root: File = prepare(scenario, label)
                            val ran: Run = runChild(root, scenario.name, k, mode)
                            assertEquals(HaltingOpsR5.EXIT_KILLED, ran.exitCode, "$label: the child was not killed (exit ${ran.exitCode}): ${ran.log}")
                            judge(scenario, root, "$label [${ops[k - 1]}]")
                        }
                    }
                }
                val futures = tasks.map { (label, task) -> label to pool.submit(task) }
                for ((label, future) in futures) {
                    try {
                        future.get(180, TimeUnit.SECONDS)
                    } catch (e: Throwable) {
                        failures += "$label: ${e.cause?.message ?: e.message}"
                    }
                }
            }
            assertTrue(killPoints >= 60, "fewer kill points than expected: $killPoints")
            assertEquals(emptyList(), failures, "crash consistency violations")
        } finally {
            pool.shutdownNow()
        }
    }
}
