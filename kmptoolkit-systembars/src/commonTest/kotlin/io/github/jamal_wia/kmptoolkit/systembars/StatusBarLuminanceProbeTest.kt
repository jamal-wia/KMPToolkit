package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The probe's own contract, from its KDoc: a trigger reaches whoever is listening, and a burst of
 * triggers costs at most one extra sample rather than one per call.
 *
 * Covered here directly rather than only through [AutoSystemBarsIconStyle], which uses the probe as
 * a stub in its lifecycle tests and so could not notice the conflation breaking.
 */
class StatusBarLuminanceProbeTest {

    @Test
    fun `a trigger is delivered to an active collector`() = runTest {
        val probe: StatusBarLuminanceProbe = createStatusBarLuminanceProbe()
        var received = 0
        val job = launch { probe.triggers.collect { received++ } }
        yield() // Let the collector subscribe before anything is emitted.

        probe.triggerRecalculation()
        yield()

        assertEquals(1, received)
        job.cancel()
    }

    @Test
    fun `a burst of triggers conflates to a single delivered signal`() = runTest {
        val probe: StatusBarLuminanceProbe = createStatusBarLuminanceProbe()
        var received = 0
        val job = launch { probe.triggers.collect { received++ } }
        yield()

        // Ten background changes before the sampler gets a chance to run must not queue ten samples.
        repeat(10) { probe.triggerRecalculation() }
        yield()

        assertEquals(1, received, "a burst must conflate to one pending sample")
        job.cancel()
    }
}
