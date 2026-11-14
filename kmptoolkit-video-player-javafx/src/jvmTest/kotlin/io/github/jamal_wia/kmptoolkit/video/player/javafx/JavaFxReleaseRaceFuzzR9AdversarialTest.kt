package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 9 (concurrency storm, release racing every operation, on real JavaFX): a seeded random sequence of player calls, looping
 * flips included, each on the FX application thread or a plain thread, and a `release()` at a random step from either kind of
 * thread. Then more calls from both kinds of thread against the released player. Nothing may reach an uncaught-exception handler
 * (the FX thread's included: an NPE inside JavaFX at a loop point was round 3's J4), the released player reads `Idle` with
 * its flows reset, every call after the release is silent, and a fresh player still prepares and plays afterwards.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxReleaseRaceFuzzR9AdversarialTest {

    @Test
    fun `release at a random step from the FX thread or a plain thread among random calls is clean`() {
        assumeJavaFxMedia()
        // The scenario (video clip, same seeds, steps and checks) runs in ReleaseRaceChildMain, a child JVM: a known
        // CoreVideo crash of the child is retried, anything else fails.
        val run: ChildRun = runChildRetryingKnownCrash(ReleaseRaceChildMain::class.java.name, timeoutS = 400L)
        assertEquals(0, run.exit, "child exit; ${run.output}")
        assertEquals("", run.results()["failures"], "release race; ${run.output}")
    }
}
