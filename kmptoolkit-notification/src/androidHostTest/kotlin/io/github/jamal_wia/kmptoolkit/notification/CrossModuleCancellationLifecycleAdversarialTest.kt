package io.github.jamal_wia.kmptoolkit.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmHandler
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduler
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import io.github.jamal_wia.kmptoolkit.scheduler.createAlarmScheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 23 (cross-module interaction and cancellation lifecycle): the real notifier and the real scheduler
 * meeting in time. A fired alarm's handler posts a notification and arms the next occurrence; while it is
 * parked inside the notification post (the permission check), the other module's calls for the very same id
 * run: a scheduler cancel / cancelAll / reschedule, a notifier cancel / cancelAll, the handler's coroutine
 * being cancelled, and the process being replaced between the post and the re-arm. Documented behaviour only:
 * a scheduler cancel "removes only an alarm that has not fired yet" and leaves what a fired alarm left on
 * screen, a notifier cancel never disarms an alarm on Android, a post that a cancel overtook is `Coalesced`,
 * and a failed or cancelled call leaves no stuck slot, claim or half-armed alarm.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrossModuleCancellationLifecycleAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val alarmManager: AlarmManager get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarms: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val tray: List<Notification> get() = shadowOf(notificationManager).allNotifications
    private val channel = NotificationChannelSpec(id = "xmod", name = "Cross module")

    private val first: Long = 9_000_000_000_000L
    private val second: Long = first + 86_400_000L
    private val third: Long = second + 86_400_000L

    private class GatedPermissions(
        private val delegate: PermissionController,
        val entered: CountDownLatch,
        val gate: CompletableDeferred<Unit>,
    ) : PermissionController by delegate {
        override suspend fun check(permission: Permission): PermissionStatus {
            entered.countDown()
            gate.await()
            return delegate.check(permission)
        }
    }

    private class Process(
        val notifier: Notifier,
        val coalescer: ProgressCoalescer,
        val entered: CountDownLatch,
        val gate: CompletableDeferred<Unit>,
    )

    private fun newProcess(): Process {
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val permissions = GatedPermissions(FakePermissionController(defaultStatus = PermissionStatus.Granted), entered, gate)
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: Notifier = AndroidNotifier(app, permissions, NotificationRenderer(app, "test.xmod.ACTION"), coalescer)
        return Process(notifier, coalescer, entered, gate)
    }

    /** The reminder a consumer writes: post at fire time, then arm the next occurrence from the same handler. */
    private class Reminder(
        private val notifier: Notifier,
        private val schedulerOf: () -> AlarmScheduler,
        private val channel: NotificationChannelSpec,
        private val nextFireAt: Long,
        private val results: MutableList<NotificationResult>,
        private val done: CountDownLatch,
    ) : AlarmHandler {
        override val type: String = "REMINDER"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            results += notifier.post(alarm.id, LocalNotification(alarm.notification.title, alarm.notification.body, channel))
            val rearm: AlarmScheduleResult = schedulerOf().schedule(alarm.copy(fireAtEpochMillis = nextFireAt))
            check(rearm == AlarmScheduleResult.Exact) { "re-arm answered $rearm" }
            done.countDown()
        }
    }

    private fun alarm(at: Long): ScheduledAlarm =
        ScheduledAlarm("rem", "REMINDER", at, AlarmNotification("Time", "Drink", "xmod"))

    private fun receiver(): BroadcastReceiver {
        val type: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver")
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as BroadcastReceiver
    }

    private fun fireOnlyArmedAlarm() {
        val armed: List<ShadowAlarmManager.ScheduledAlarm> = shadowAlarms.scheduledAlarms
        assertEquals(1, armed.size, "exactly one alarm must be armed before it fires")
        receiver().onReceive(app, shadowOf(requireNotNull(armed.single().operation)).savedIntent)
    }

    private fun armedInstants(): List<Long> = shadowAlarms.scheduledAlarms.map { it.triggerAtMs }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(15_000L) { block() } }

    /** Boots a scheduler plus handler over [process], arms the first alarm and fires it; the handler parks in its post. */
    private fun fireIntoParkedHandler(process: Process, results: MutableList<NotificationResult>, done: CountDownLatch): AlarmScheduler {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val holder: AtomicReference<AlarmScheduler> = AtomicReference()
        val handler = Reminder(process.notifier, { requireNotNull(holder.get()) }, channel, third, results, done)
        val scheduler: AlarmScheduler = createAlarmScheduler(app, handlerProvider = { listOf(handler) })
        holder.set(scheduler)
        assertEquals(AlarmScheduleResult.Exact, blocking { scheduler.schedule(alarm(first)) })
        fireOnlyArmedAlarm()
        assertTrue(process.entered.await(10, TimeUnit.SECONDS), "the handler never reached its notification post")
        return scheduler
    }

    @Test
    fun `scheduler cancel cancelAll and reschedule of the id while its handler is parked in the notification post leave the post and the next alarm intact`() {
        val process: Process = newProcess()
        val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
        val done = CountDownLatch(1)
        val scheduler: AlarmScheduler = fireIntoParkedHandler(process, results, done)
        try {
            // The alarm has fired: a cancel has nothing left to disarm, and must not wedge anything.
            blocking { scheduler.cancel("rem") }
            assertEquals(emptyList(), armedInstants(), "after cancel nothing is armed for the id")
            assertEquals(AlarmScheduleResult.Exact, blocking { scheduler.schedule(alarm(second)) })
            assertEquals(listOf(second), armedInstants())
            blocking { scheduler.cancelAll(listOf("rem", "never-armed")) }
            assertEquals(emptyList(), armedInstants(), "cancelAll after a reschedule disarms it")
            assertEquals(AlarmScheduleResult.Exact, blocking { scheduler.schedule(alarm(second)) })
        } finally {
            process.gate.complete(Unit)
        }
        assertTrue(done.await(15, TimeUnit.SECONDS), "the handler did not finish after the gate opened")

        assertEquals(listOf<NotificationResult>(NotificationResult.Posted), results, "the scheduler never cancels a post in flight")
        assertEquals(1, tray.size, "what a fired alarm left on screen stays, as documented")
        assertEquals(listOf(third), armedInstants(), "the handler's own re-arm replaced the consumer's alarm: one alarm, the newest time")
        assertEquals(0, process.coalescer.claimCountForTest())
    }

    @Test
    fun `notifier cancel and cancelAll while the handler is parked in the post drop that post but never touch the alarm the handler arms next`() {
        for (useCancelAll: Boolean in listOf(false, true)) {
            val process: Process = newProcess()
            val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
            val done = CountDownLatch(1)
            val scheduler: AlarmScheduler = fireIntoParkedHandler(process, results, done)
            try {
                if (useCancelAll) process.notifier.cancelAll() else process.notifier.cancel("rem")
                assertEquals(1, armedInstants().size, "the notifier's cancel never disarms the alarm of the same id on Android")
            } finally {
                process.gate.complete(Unit)
            }
            assertTrue(done.await(15, TimeUnit.SECONDS), "the handler did not finish (cancelAll=$useCancelAll)")

            assertEquals(listOf<NotificationResult>(NotificationResult.Coalesced), results, "a post overtaken by a cancel is Coalesced (cancelAll=$useCancelAll)")
            assertEquals(0, tray.size, "the cancelled post must not come back (cancelAll=$useCancelAll)")
            assertEquals(listOf(third), armedInstants(), "the handler still armed the next occurrence (cancelAll=$useCancelAll)")
            assertEquals(0, process.coalescer.claimCountForTest())
            blocking { scheduler.cancel("rem") }
            assertEquals(emptyList(), armedInstants())
        }
    }

    @Test
    fun `a handler cancelled while parked in the post leaves no notification no claim no half armed alarm and no stuck slot`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val process: Process = newProcess()
        val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
        val done = CountDownLatch(1)
        val holder: AtomicReference<AlarmScheduler> = AtomicReference()
        val handler = Reminder(process.notifier, { requireNotNull(holder.get()) }, channel, third, results, done)
        val scheduler: AlarmScheduler = createAlarmScheduler(app, handlerProvider = { listOf(handler) })
        holder.set(scheduler)
        assertEquals(AlarmScheduleResult.Exact, blocking { scheduler.schedule(alarm(first)) })

        val scope = CoroutineScope(Dispatchers.IO)
        val job: Job = scope.launch { handler.onFire(alarm(first)) }
        assertTrue(process.entered.await(10, TimeUnit.SECONDS), "the handler never reached its notification post")
        runBlocking { withTimeout(15_000L) { job.cancel(); job.join() } }

        assertTrue(job.isCancelled)
        assertEquals(1L, done.count, "the handler's re-arm never ran: it was cancelled inside the post")
        assertEquals(emptyList(), results.toList())
        assertEquals(0, tray.size, "a cancelled post shows nothing")
        assertEquals(0, process.coalescer.claimCountForTest(), "the cancelled post released its claim")
        assertEquals(listOf(first), armedInstants(), "the alarm armed before is exactly as it was: not half armed, not lost")

        process.gate.complete(Unit)
        // The notifier is usable at once and the id is a fresh run, not a Coalesced ghost.
        assertEquals(
            NotificationResult.Posted,
            blocking { process.notifier.post("rem", LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(40))) },
        )
        assertEquals(
            NotificationResult.Coalesced,
            blocking { process.notifier.post("rem", LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(41))) },
            "control: the baseline of the completed post stands, so the cancelled one left none behind",
        )
        // The id owns four slots: repeated schedules and a cancel never meet a saturated id.
        repeat(9) { round: Int ->
            assertEquals(AlarmScheduleResult.Exact, blocking { scheduler.schedule(alarm(first + round)) }, "schedule $round after the cancelled handler")
        }
        assertEquals(1, armedInstants().size)
        blocking { scheduler.cancel("rem") }
        assertEquals(emptyList(), armedInstants())
    }

    @Test
    fun `a new process after a death between the post and the re-arm re-arms from the consumer table and keeps every id independent`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // Process 1: the alarm fires, the handler posts, the process dies before it re-arms (the handler never schedules).
        val process1: Process = newProcess()
        process1.gate.complete(Unit)
        val posted = CountDownLatch(1)
        val scheduler1: AlarmScheduler = createAlarmScheduler(
            app,
            handlerProvider = {
                listOf(
                    object : AlarmHandler {
                        override val type: String = "REMINDER"
                        override suspend fun onFire(alarm: ScheduledAlarm) {
                            process1.notifier.post(alarm.id, LocalNotification("from process 1", "b", channel))
                            posted.countDown()
                        }
                    },
                )
            },
        )
        assertEquals(AlarmScheduleResult.Exact, blocking { scheduler1.schedule(alarm(first)) })
        fireOnlyArmedAlarm()
        assertTrue(posted.await(15, TimeUnit.SECONDS), "process 1's handler never finished its post")
        assertEquals(1, tray.size, "process 1 showed its notification")

        // Process 2: new notifier, new scheduler (new registration); the consumer's reconcile pass re-arms its table.
        val process2: Process = newProcess()
        process2.gate.complete(Unit)
        val scheduler2: AlarmScheduler = createAlarmScheduler(app)
        assertEquals(AlarmScheduleResult.Exact, blocking { scheduler2.schedule(alarm(second)) })
        assertEquals(listOf(second), armedInstants(), "the fired alarm's slot is retired by the next schedule: one alarm, the new time")

        assertEquals(1, tray.size, "the previous process's notification is still showing")
        assertEquals(
            NotificationResult.Posted,
            blocking { process2.notifier.post("rem", LocalNotification("from process 2", "b", channel)) },
            "a fresh process has no baseline, so even the same content posts",
        )
        assertEquals(listOf("from process 2"), tray.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() })
        process2.notifier.cancel("rem")
        assertEquals(0, tray.size, "the notifier removes it, whichever process posted it")
        assertEquals(listOf(second), armedInstants(), "and never touches the alarm of the same id")
        blocking { scheduler2.cancel("rem") }
        assertEquals(emptyList(), armedInstants())
        assertEquals(0, process1.coalescer.claimCountForTest())
        assertEquals(0, process2.coalescer.claimCountForTest())
    }
}
