package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Platform churn against the wake scheduler and the classifier slot: constraints flipping while wakes are armed and
 * cancelled from other threads, and two transports created at once. Real threads, bounded joins.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerWakeChurnAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val job = Job()
    private val workName = "wake.churn.${System.nanoTime()}"
    private val config = WorkManagerWakeConfig(
        uniqueWorkName = workName,
        requiresNetwork = true,
        engineWait = 200.milliseconds,
        drainBudget = 1.seconds,
    )
    private lateinit var engine: UploaderEngine

    @BeforeTest
    fun setUp() {
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        engine = testEngine(TestUploaderStore(), listOf(TestHandler()), CoroutineScope(Dispatchers.Default + job))
        UploaderEngineRegistry.register(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        job.cancel()
        UploaderEngineRegistry.current?.let(UploaderEngineRegistry::unregister)
    }

    private fun infos(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get()

    @Test
    fun `fifty rounds of arming and cancelling while constraints flip leave at most one live wake`() {
        val scheduler = WorkManagerWakeScheduler(context, config, io.github.jamal_wia.kmptoolkit.logging.NoopLogger)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val done = AtomicBoolean(false)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val flipper = thread(isDaemon = true) {
            runCatching {
                while (!done.get()) {
                    infos().filter { !it.state.isFinished }.forEach { runCatching { driver.setAllConstraintsMet(it.id) } }
                    Thread.sleep(2)
                }
            }.onFailure { failures += it }
        }
        val armers: List<Thread> = List(2) { who ->
            thread(isDaemon = true) {
                runCatching {
                    repeat(50) { round ->
                        scheduler.scheduleWake()
                        if ((round + who) % 3 == 0) scheduler.cancelWake()
                        val live: Int = infos().count { !it.state.isFinished }
                        assertTrue(live <= 1, "live wakes: ${infos().map { it.state }}")
                    }
                }.onFailure { failures += it }
            }
        }
        armers.forEach { it.join(60_000) }
        done.set(true)
        flipper.join(10_000)

        assertTrue(failures.isEmpty(), "failures: $failures")
        scheduler.cancelWake()
        scheduler.scheduleWake()
        assertEquals(1, infos().count { !it.state.isFinished }, "a fresh arm after the churn is the one live wake")
    }

    @Test
    fun `two transports created at once leave the classifier slot holding one of the two`() {
        repeat(100) {
            val first: (UploadResult) -> SettleResult = { SettleResult.Delivered }
            val second: (UploadResult) -> SettleResult = { SettleResult.Failed(null) }
            val threads: List<Thread> = listOf(
                thread { createWorkManagerUploadTransport(context, UploadTransportConfig(), first) },
                thread { createWorkManagerUploadTransport(context, UploadTransportConfig(), second) },
            )
            threads.forEach { it.join(10_000) }

            val held: ((UploadResult) -> SettleResult)? = runBlocking { UploadClassifierRegistry.await(1_000L) }

            assertTrue(held === first || held === second, "the slot holds a classifier neither transport registered")
        }
    }
}
