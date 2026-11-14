package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The retry path of [runChildRetryingKnownCrash], without JavaFX: a child that halts with 137 after writing an
 * `hs_err` naming CoreVideo is retried and then passes; the same crash with another frame, or three known
 * crashes in a row, fails. Every child is bounded by the helper's own timeout.
 */
class CrashTolerantChildAdversarialTest {

    /** A scratch directory per test class: the fake crash logs must not land among the real ones in build/hs_err. */
    private val scratch: File = Files.createTempDirectory("kmptoolkit-crash-retry").toFile()

    @AfterTest
    fun deleteScratch() {
        scratch.deleteRecursively()
    }

    private fun marker(): File = File(File(Files.createTempDirectory(scratch.toPath(), "case").toString()), "marker")

    @Test
    fun `a child that crashes once with the known CoreVideo frame is retried and passes`() {
        val run: ChildRun = runChildRetryingKnownCrash(CrashRetryMain::class.java.name, listOf(marker().path), timeoutS = 60L, hsErrDir = scratch.path)
        assertEquals(2, run.attempts, run.output)
        assertEquals(0, run.exit, run.output)
        assertEquals(1, run.knownCrashes.size)
        assertEquals("true", run.results()["survived"], run.output)
    }

    @Test
    fun `a crash whose frame is not CoreVideo fails at once`() {
        assertFailsWith<AssertionError> {
            runChildRetryingKnownCrash(CrashRetryMain::class.java.name, listOf(marker().path, "other"), timeoutS = 60L, hsErrDir = scratch.path)
        }
    }

    @Test
    fun `an exit of 137 with no hs_err is not retried and fails`() {
        val failure: AssertionError = assertFailsWith<AssertionError> {
            runChildRetryingKnownCrash(CrashRetryMain::class.java.name, listOf(marker().path, "nolog"), timeoutS = 60L, hsErrDir = scratch.path)
        }
        assertTrue("not the known CoreVideo crash" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `three known crashes in a row fail`() {
        val failure: AssertionError = assertFailsWith<AssertionError> {
            runChildRetryingKnownCrash(CrashRetryMain::class.java.name, listOf(marker().path, "always"), timeoutS = 60L, hsErrDir = scratch.path)
        }
        assertTrue("3 of 3" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the knobs forwarded to a child JVM keep every kmptoolkit test property except the crash log directory`() {
        val properties: Properties = Properties().apply {
            setProperty("kmptoolkit.test.r9.rel.seeds", "20")
            setProperty("kmptoolkit.test.seed", "777")
            setProperty(HS_ERR_DIR_PROPERTY, File(scratch, "parent-hs-err").path)
            setProperty("kmptoolkit.soak.steps", "5")
            setProperty("user.dir", "somewhere")
        }
        assertEquals(
            listOf("-Dkmptoolkit.test.r9.rel.seeds=20", "-Dkmptoolkit.test.seed=777"),
            forwardedTestKnobs(properties),
            "only the kmptoolkit.test knobs are forwarded and the parent's hs_err directory is not one of them",
        )
        val callerDir: String = File(scratch, "caller-hs-err").path
        val args: List<String> = forwardedTestKnobs(properties) + childJvmCrashGuardArgs(callerDir)
        assertEquals(
            listOf("-D$HS_ERR_DIR_PROPERTY=$callerDir"),
            args.filter { it.startsWith("-D$HS_ERR_DIR_PROPERTY=") },
            "the child JVM gets exactly one hs_err directory, the caller's, in either argument order",
        )
    }
}
