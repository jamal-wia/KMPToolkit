package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 12 (documentation as a test): the code samples of docs/kmptoolkit-scheduler (02-getting-started,
 * 03-guide, 06-testing) and the observable sentences around them, compiled against the public API and
 * run. Samples that need an app (R.drawable, a Koin module, a store) are reduced to the lines that
 * touch this library; nothing documented is changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SchedulerDocsSamplesAdversarialTest.OrderRecordingShadow::class])
class SchedulerDocsSamplesAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        OrderRecordingShadow.cancelled.clear()
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    /** 02-getting-started step 3, with the notification replaced by a record of what the handler saw. */
    private class StudyReminderHandler(private val seen: MutableList<ScheduledAlarm>, private val fired: CountDownLatch) : AlarmHandler {
        override val type: String = "STUDY_REMINDER"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            seen += alarm
            fired.countDown()
        }
    }

    private fun fire(operationOf: ShadowAlarmManager.ScheduledAlarm) {
        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(context, shadowOf(requireNotNull(operationOf.operation)).savedIntent)
    }

    @Test
    fun `getting started steps 3 to 5 arm the sample alarm exactly and deliver every field to the sample handler`() {
        val seen: MutableList<ScheduledAlarm> = Collections.synchronizedList(mutableListOf())
        val fired = CountDownLatch(1)
        val scheduler: AlarmScheduler = createAlarmScheduler(
            context = context,
            handlers = listOf(StudyReminderHandler(seen, fired)),
        )
        val lessonId = "lesson-7"
        val fireAtEpochMillis = 1_900_000_000_000L
        val result: AlarmScheduleResult = runBlocking {
            scheduler.schedule(
                ScheduledAlarm(
                    id = "study-$lessonId",
                    type = "STUDY_REMINDER",
                    fireAtEpochMillis = fireAtEpochMillis,
                    notification = AlarmNotification(title = "Time to study", body = "Lesson 7", channelId = "reminders"),
                    payload = mapOf("lessonId" to lessonId),
                ),
            )
        }
        val verdict: String = when (result) {
            AlarmScheduleResult.Exact -> "exact"
            is AlarmScheduleResult.Inexact -> "inexact ${result.reason}"
            is AlarmScheduleResult.Failed -> "failed ${result.reason}"
        }
        assertEquals("exact", verdict)
        assertEquals(fireAtEpochMillis, shadowAlarmManager.scheduledAlarms.single().triggerAtMs)

        fire(shadowAlarmManager.nextScheduledAlarm!!)
        assertTrue(fired.await(10, TimeUnit.SECONDS), "the sample handler never ran")
        val alarm: ScheduledAlarm = seen.single()
        assertEquals("study-lesson-7", alarm.id)
        assertEquals("Time to study", alarm.notification.title)
        assertEquals("Lesson 7", alarm.notification.body)
        assertEquals("reminders", alarm.notification.channelId)
        assertEquals(mapOf("lessonId" to "lesson-7"), alarm.payload)
        assertEquals(fireAtEpochMillis, alarm.fireAtEpochMillis)
    }

    @Test
    fun `without the optional permission the sample alarm is armed inexactly and says so`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler: AlarmScheduler = createAlarmScheduler(context = context)
        val result: AlarmScheduleResult = runBlocking {
            scheduler.schedule(
                ScheduledAlarm("study-1", "STUDY_REMINDER", 1_000L, AlarmNotification("t", "b", "reminders")),
            )
        }
        val inexact = result as? AlarmScheduleResult.Inexact
        assertNotNull(inexact, "documented: skipping the permission is legitimate and answers Inexact, was $result")
        assertEquals(InexactReason.EXACT_ALARM_PERMISSION_MISSING, inexact.reason)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "an Inexact alarm is armed")
    }

    @Test
    fun `two createAlarmScheduler calls with different handler lists - the last one wins, as the common mistakes say`() {
        val firstSeen: MutableList<ScheduledAlarm> = Collections.synchronizedList(mutableListOf())
        val secondSeen: MutableList<ScheduledAlarm> = Collections.synchronizedList(mutableListOf())
        val firstFired = CountDownLatch(1)
        val secondFired = CountDownLatch(1)
        val first: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(StudyReminderHandler(firstSeen, firstFired)))
        val second: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(StudyReminderHandler(secondSeen, secondFired)))
        runBlocking {
            // The older instance still arms: only the registration at fire time is process-wide.
            first.schedule(ScheduledAlarm("a", "STUDY_REMINDER", 1L, AlarmNotification("t", "b", "c")))
        }
        fire(shadowAlarmManager.nextScheduledAlarm!!)
        assertTrue(secondFired.await(10, TimeUnit.SECONDS), "the handler list of the last call must receive the alarm")
        assertEquals(1, secondSeen.size)
        assertEquals(0, firstSeen.size, "the first list must have stopped receiving alarms")
        assertTrue(first !== second)
    }

    /** 03-guide "Reboot re-arming": the receiver class of the guide, with the store reduced to a list. */
    private class ReArmAlarmsReceiver(
        private val scheduler: AlarmScheduler,
        private val upcoming: List<ScheduledAlarm>,
        private val done: CountDownLatch,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            val pendingResult: PendingResult? = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    upcoming.forEach { scheduler.schedule(it) }
                } finally {
                    pendingResult?.finish()
                    done.countDown()
                }
            }
        }
    }

    @Test
    fun `the guide's boot receiver arms every upcoming alarm once and a repeated boot broadcast arms none twice`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val upcoming: List<ScheduledAlarm> = (0 until 40).map {
            ScheduledAlarm("reminder-$it", "T", 5_000L + it, AlarmNotification("t$it", "b", "c"))
        }
        repeat(3) { boot: Int ->
            val done = CountDownLatch(1)
            ReArmAlarmsReceiver(scheduler, upcoming, done).onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertTrue(done.await(10, TimeUnit.SECONDS), "boot $boot never finished")
            assertEquals(upcoming.size, shadowAlarmManager.scheduledAlarms.size, "boot $boot: one alarm per upcoming reminder")
        }
        // The guard of the sample: another action is ignored.
        val ignored = CountDownLatch(1)
        ReArmAlarmsReceiver(scheduler, upcoming, ignored).onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertEquals(1L, ignored.count)
    }

    @Test
    fun `cancelAll cancels exactly the ids passed, in order, and cancelling an id that is not armed is a no-op`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking {
            listOf("a", "b", "c", "d", "e").forEach {
                scheduler.schedule(ScheduledAlarm(it, "T", 1_000L, AlarmNotification("t", "b", "c")))
            }
            OrderRecordingShadow.cancelled.clear()
            // Not a palindrome, so a reversed or sorted order cannot pass: c before a before b.
            scheduler.cancelAll(listOf("c", "never-armed", "a", "b"))
            // path-hygiene-ignore: PH3 the value is the data URI of a pending intent (scheme and id), a logical name split on '/', not a host path
            val order: List<String> = OrderRecordingShadow.cancelled.map { it.substringAfterLast('/') }
            assertEquals(listOf("c", "a", "b"), order, "cancelled pending intents, in the order of the ids passed")

            OrderRecordingShadow.cancelled.clear()
            scheduler.cancelAll(listOf("d", "d"))
            // path-hygiene-ignore: PH3 the value is the data URI of a pending intent (scheme and id), a logical name split on '/', not a host path
            assertEquals(listOf("d"), OrderRecordingShadow.cancelled.map { it.substringAfterLast('/') }, "a repeated id cancels once")
        }
        val left: Set<String> = shadowAlarmManager.scheduledAlarms
            .map { shadowOf(it.operation).savedIntent.getStringExtra("alarm_id").orEmpty() }.toSet()
        assertEquals(setOf("e"), left, "cancelAll cancelled something it was not given")
        runBlocking { scheduler.cancelAll(emptyList()) }
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "an empty collection is a no-op")
    }

    @Test
    fun `06-testing handlerFor samples hold on a list, a set and an empty collection`() {
        val study: AlarmHandler = object : AlarmHandler {
            override val type: String = "STUDY_REMINDER"
            override suspend fun onFire(alarm: ScheduledAlarm) = Unit
        }
        val other: AlarmHandler = object : AlarmHandler {
            override val type: String = "STUDY_REMINDER"
            override suspend fun onFire(alarm: ScheduledAlarm) = Unit
        }
        val handlers: List<AlarmHandler> = listOf(study, other)
        assertSame(study, handlers.handlerFor("STUDY_REMINDER"), "duplicate types: the first wins")
        assertNull(handlers.handlerFor("SOMETHING_ELSE"))
        assertNull(handlers.handlerFor("study_reminder"), "case sensitive")
        assertNull(emptyList<AlarmHandler>().handlerFor("STUDY_REMINDER"))
        assertSame(study, linkedSetOf(study).handlerFor("STUDY_REMINDER"))
    }

    @Implements(AlarmManager::class)
    class OrderRecordingShadow : ShadowAlarmManager() {
        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (operation != null) cancelled += shadowOf(operation).savedIntent.data.toString()
            super.cancel(operation)
        }

        companion object {
            val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        }
    }
}
