package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.nio.file.Files
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
    private val scratch: File = Files.createTempDirectory("kmptoolkit-crash-retry").toFile().apply { deleteOnExit() }

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
    fun `three known crashes in a row fail`() {
        val failure: AssertionError = assertFailsWith<AssertionError> {
            runChildRetryingKnownCrash(CrashRetryMain::class.java.name, listOf(marker().path, "always"), timeoutS = 60L, hsErrDir = scratch.path)
        }
        assertTrue("3 of 3" in failure.message.orEmpty(), failure.message)
    }
}
