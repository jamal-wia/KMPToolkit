package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 17 (upgrade compatibility): an alarm armed by 3.0.0 or 4.0.0 lives in the platform's AlarmManager
 * across an app update, so its shape is a wire format. Every earlier upgrade test built the "old" alarm
 * with the production code under test, which would follow a rename. This one writes the shape from
 * literals taken from the 3.0.0 and 4.0.0 sources (git show 3.0.0:.../AlarmIntents.kt): component class
 * name, extra names, default keys, the derived scheme and the request code, and checks the current code
 * still finds, fires, replaces and cancels it, and survives newer or damaged extras without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmWireFormatFixtureAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    /** Literal 3.0.0 / 4.0.0 shape, written without touching AlarmIntents. */
    private fun armAsEarlierVersion(
        id: String,
        scheme: String = context.packageName.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '+') it else '-' }.joinToString("") + ".alarm",
        idKey: String = "alarm_id",
        typeKey: String = "alarm_type",
        fireAt: Long = 9_000_000_000_000L,
        title: String = "old title",
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent: Intent = Intent().apply {
            component = ComponentName(context.packageName, "io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver")
            data = Uri.Builder().scheme(scheme).appendPath(id).build() // exactly the 3.0.0 and 4.0.0 call
            putExtra(idKey, id)
            putExtra(typeKey, "OLD")
            putExtra("kmptoolkit_alarm_fire_at", fireAt)
            putExtra("kmptoolkit_alarm_title", title)
            putExtra("kmptoolkit_alarm_body", "old body")
            putExtra("kmptoolkit_alarm_channel", "old-channel")
            putExtra("kmptoolkit_alarm_payload", Bundle().apply { putString("route", "lesson/7") })
            extras()
        }
        val pending: PendingIntent = PendingIntent.getBroadcast(
            context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        return pending
    }

    private class Recording(override val type: String, val seen: MutableList<ScheduledAlarm>, val latch: CountDownLatch) : AlarmHandler {
        override suspend fun onFire(alarm: ScheduledAlarm) { seen += alarm; latch.countDown() }
    }

    private fun fire() {
        @Suppress("DEPRECATION")
        val intents: List<Intent> = shadowAlarmManager.scheduledAlarms.map { shadowOf(requireNotNull(it.operation)).savedIntent }
        intents.forEach { AlarmReceiver().onReceive(context, it) }
    }

    @Test
    fun `an alarm in the 3_0_0 and 4_0_0 shape is delivered whole to the handler of the current version`() {
        val seen: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val latch = CountDownLatch(1)
        createAlarmScheduler(context, handlers = listOf(Recording("OLD", seen, latch)))
        armAsEarlierVersion("study-lesson-7")
        fire()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(
            ScheduledAlarm(
                id = "study-lesson-7", type = "OLD", fireAtEpochMillis = 9_000_000_000_000L,
                notification = AlarmNotification("old title", "old body", "old-channel"),
                payload = mapOf("route" to "lesson/7"),
            ),
            seen.single(),
        )
    }

    @Test
    fun `cancel replace and cancelAll of the current version reach an alarm armed in the earlier shape`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val ids: List<String> = listOf("a", "reminder-42", "with space", "ünï", "x/y")
        ids.forEach { armAsEarlierVersion(it) }
        assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size)

        runBlocking { scheduler.cancel("a") }
        assertEquals(ids.size - 1, shadowAlarmManager.scheduledAlarms.size, "cancel found the earlier alarm")

        val replaced: AlarmScheduleResult = runBlocking {
            scheduler.schedule(ScheduledAlarm("reminder-42", "NEW", 9_100_000_000_000L, AlarmNotification("new", "b", "c")))
        }
        assertEquals(AlarmScheduleResult.Exact, replaced)
        assertEquals(ids.size - 1, shadowAlarmManager.scheduledAlarms.size, "replace retired the earlier alarm instead of adding a second")
        assertTrue(shadowAlarmManager.scheduledAlarms.any { it.triggerAtMs == 9_100_000_000_000L })

        runBlocking { scheduler.cancelAll(ids) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `a configured scheme and configured key names written by the earlier version keep working literally`() {
        val seen: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val latch = CountDownLatch(1)
        val config = AlarmSchedulerConfig(alarmIntentScheme = "myapp.reminders", alarmIdKey = "my_id", alarmTypeKey = "my_type")
        val scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(Recording("OLD", seen, latch)), config = config)
        armAsEarlierVersion("r1", scheme = "myapp.reminders", idKey = "my_id", typeKey = "my_type")
        fire()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals("r1", seen.single().id)
        runBlocking { scheduler.cancel("r1") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `extras of a newer or damaged shape never crash the receiver and degrade to empty text`() {
        val seen: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val latch = CountDownLatch(4)
        createAlarmScheduler(context, handlers = listOf(Recording("OLD", seen, latch)))
        // Wrong types where strings and longs are read, an unknown newer extra, a payload with foreign values.
        armAsEarlierVersion("newer-1") {
            putExtra("kmptoolkit_alarm_title", 7)
            putExtra("kmptoolkit_alarm_fire_at", "not a long")
            putExtra("kmptoolkit_alarm_priority", "high")
            putExtra(
                "kmptoolkit_alarm_payload",
                Bundle().apply {
                    putString("ok", "yes"); putInt("n", 3); putBoolean("b", true); putBundle("nested", Bundle())
                    putStringArray("arr", arrayOf("x")); putString("nullvalue", null)
                },
            )
        }
        // Truncated: the optional extras are simply absent.
        armAsEarlierVersion("trunc-2") {
            removeExtra("kmptoolkit_alarm_title"); removeExtra("kmptoolkit_alarm_body")
            removeExtra("kmptoolkit_alarm_channel"); removeExtra("kmptoolkit_alarm_payload"); removeExtra("kmptoolkit_alarm_fire_at")
        }
        // Payload extra is a String instead of a Bundle.
        armAsEarlierVersion("shape-3") { putExtra("kmptoolkit_alarm_payload", "oops") }
        // Everything optional is the wrong type at once.
        armAsEarlierVersion("shape-4") {
            putExtra("kmptoolkit_alarm_body", 1L); putExtra("kmptoolkit_alarm_channel", true); putExtra("kmptoolkit_alarm_payload", 5)
        }
        fire()
        assertTrue(latch.await(10, TimeUnit.SECONDS), "every one of the four reaches the handler")
        val byId: Map<String, ScheduledAlarm> = seen.associateBy { it.id }
        assertEquals(AlarmNotification("", "old body", "old-channel"), byId.getValue("newer-1").notification)
        assertEquals(mapOf("ok" to "yes"), byId.getValue("newer-1").payload)
        assertEquals(0L, byId.getValue("newer-1").fireAtEpochMillis)
        assertEquals(AlarmNotification("", "", ""), byId.getValue("trunc-2").notification)
        assertEquals(emptyMap(), byId.getValue("trunc-2").payload)
        assertEquals(emptyMap(), byId.getValue("shape-3").payload)
        assertEquals(AlarmNotification("old title", "", ""), byId.getValue("shape-4").notification)
    }

    @Test
    fun `an earlier alarm whose id or type extra is missing blank or of the wrong type is dropped and the next good one still runs`() {
        val seen: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val latch = CountDownLatch(1)
        createAlarmScheduler(context, handlers = listOf(Recording("OLD", seen, latch)))
        armAsEarlierVersion("no-id") { removeExtra("alarm_id") }
        armAsEarlierVersion("blank-id") { putExtra("alarm_id", "   ") }
        armAsEarlierVersion("int-id") { putExtra("alarm_id", 4) }
        armAsEarlierVersion("no-type") { removeExtra("alarm_type") }
        armAsEarlierVersion("blank-type") { putExtra("alarm_type", "") }
        armAsEarlierVersion("good")
        fire()
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(listOf("good"), seen.map { it.id })
    }

    @Test
    fun `the identity of an alarm is the scheme dot alarm of the application id and the request code is the id hash`() {
        // Pins what an installed alarm of an earlier version was matched on, from the literal rule, not from AlarmIntents.
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val expectedScheme: String = context.packageName.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '+') it else '-' }.joinToString("") + ".alarm"
        runBlocking { scheduler.schedule(ScheduledAlarm("pin", "T", 9_000_000_000_000L, AlarmNotification("t", "b", "c"))) }
        val armed: Intent = shadowOf(requireNotNull(shadowAlarmManager.scheduledAlarms.single().operation)).savedIntent
        assertEquals(expectedScheme, armed.data?.scheme)
        assertEquals("/pin", armed.data?.path)
        assertEquals(ComponentName(context.packageName, "io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver"), armed.component)
        assertEquals("pin", armed.getStringExtra("alarm_id"))
        assertEquals("T", armed.getStringExtra("alarm_type"))
        assertEquals(9_000_000_000_000L, armed.getLongExtra("kmptoolkit_alarm_fire_at", -1L))
        assertEquals("t", armed.getStringExtra("kmptoolkit_alarm_title"))
        assertEquals("b", armed.getStringExtra("kmptoolkit_alarm_body"))
        assertEquals("c", armed.getStringExtra("kmptoolkit_alarm_channel"))
        assertEquals(0, armed.getBundleExtra("kmptoolkit_alarm_payload")?.size())
        // Slot 0 uses the hash itself: the same lookup the earlier version used finds the alarm.
        val found: PendingIntent? = PendingIntent.getBroadcast(
            context, "pin".hashCode(), Intent().apply {
                component = ComponentName(context.packageName, "io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver")
                data = Uri.parse("$expectedScheme:/pin")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        assertTrue(found != null, "the lookup of the earlier version still resolves the armed alarm")
    }
}
