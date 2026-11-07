package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.runner.RunWith

/**
 * The wake scheduler against a real (test-initialised) WorkManager across a wake's whole life: a finished
 * wake must not stop the next one from being armed, a cancelled wake must not stay cancelled, and arming
 * twice must leave one job.
 *
 * Hazard: `ExistingWorkPolicy.KEEP` keeps whatever unique work exists; a terminal one must not count.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerWakeSchedulerLifecycleAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val job = Job()
    private val workName = "r2.wake.lifecycle.${System.nanoTime()}"
    private val config = WorkManagerWakeConfig(
        uniqueWorkName = workName,
        requiresNetwork = false,
        engineWait = 300.milliseconds,
        drainBudget = 2.seconds,
    )

    private class CountingEngine(private val delegate: UploaderEngine) : UploaderEngine by delegate {
        val awaited = AtomicInteger(0)
        override suspend fun awaitDrained(timeout: Duration): Boolean {
            awaited.incrementAndGet()
            return delegate.awaitDrained(timeout)
        }
    }

    private lateinit var engine: CountingEngine

    @BeforeTest
    fun setUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val inner: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), CoroutineScope(Dispatchers.Default + job))
        engine = CountingEngine(inner)
        UploaderEngineRegistry.register(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        job.cancel()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun states(): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get().map { it.state }

    /** Polls the real clock, at most ten seconds, until the unique work is exactly in [expected]. */
    private fun awaitStates(expected: List<WorkInfo.State>) {
        val deadline: Long = System.nanoTime() + 10_000_000_000L
        while (states() != expected && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(expected, states())
    }

    @Test
    fun `a wake that finished does not keep the next scheduleWake from arming a fresh job`() {
        val scheduler = createWorkManagerWakeScheduler(context, config)

        scheduler.scheduleWake()
        awaitStates(listOf(WorkInfo.State.SUCCEEDED))
        assertEquals(1, engine.awaited.get(), "the first wake did not run exactly once")

        scheduler.scheduleWake()
        val deadline: Long = System.nanoTime() + 10_000_000_000L
        while (engine.awaited.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals(2, engine.awaited.get(), "KEEP kept a finished job and the second wake never ran")
    }

    @Test
    fun `a cancelled wake does not keep the next scheduleWake from arming a fresh job`() {
        // Network required: the test WorkManager holds the job until its constraint is declared met.
        val scheduler = createWorkManagerWakeScheduler(context, config.copy(requiresNetwork = true))
        scheduler.scheduleWake()
        awaitStates(listOf(WorkInfo.State.ENQUEUED))
        scheduler.cancelWake()
        awaitStates(listOf(WorkInfo.State.CANCELLED))

        scheduler.scheduleWake()
        awaitStates(listOf(WorkInfo.State.ENQUEUED))
        val id = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)

        awaitStates(listOf(WorkInfo.State.SUCCEEDED))
        assertEquals(1, engine.awaited.get(), "the fresh wake did not run exactly once")
    }

    @Test
    fun `a wake that found no engine is retried by the platform and arming again adds no second job`() {
        UploaderEngineRegistry.unregister(engine)
        val scheduler = createWorkManagerWakeScheduler(context, config)

        scheduler.scheduleWake() // runs at once, finds no engine within its wait, asks to be retried
        scheduler.scheduleWake()
        scheduler.scheduleWake()
        awaitStates(listOf(WorkInfo.State.ENQUEUED)) // exactly one job, queued for the platform's retry

        assertEquals(0, engine.awaited.get())
    }
}
