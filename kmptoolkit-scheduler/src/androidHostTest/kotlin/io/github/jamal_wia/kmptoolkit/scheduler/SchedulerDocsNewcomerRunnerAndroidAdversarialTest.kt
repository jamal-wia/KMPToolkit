package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 24: runs the docs-only newcomer samples and the documented-defaults table of the scheduler (plain common
 * code, no Robolectric) on the Android host. The classes live in commonTest because the samples touch no platform.
 */
class SchedulerDocsNewcomerRunnerAndroidAdversarialTest {

    private val samples = SchedulerDocsNewcomerSamples()
    private val table = SchedulerDocumentedDefaultsTable()

    @Test
    fun `getting started step five`() = run { samples.gettingStartedStepFive() }

    @Test
    fun `guide reconcile pass`() = run { samples.guideReconcilePass() }

    @Test
    fun `guide ids and types`() = run { samples.guideIdsAndTypes() }

    @Test
    fun `testing doc double`() = run { samples.testingDocDouble() }

    @Test
    fun `api reference validations`() = run { samples.apiReferenceValidations() }

    @Test
    fun `documented defaults table`() = run { table.documentedDefaultsTable() }

    private fun run(block: suspend () -> Unit) {
        runBlocking { withTimeout(30_000) { block() } }
    }
}
