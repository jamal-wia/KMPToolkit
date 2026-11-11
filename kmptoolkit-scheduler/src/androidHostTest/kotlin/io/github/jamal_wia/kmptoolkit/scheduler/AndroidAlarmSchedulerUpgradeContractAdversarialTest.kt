package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * The upgrade and stability promises of `AlarmSchedulerConfig`, taken literally: what exactly
 * happens to an alarm armed under an earlier scheme or key, that nothing a process cannot keep
 * stable (locale, time zone, DST) takes part in an alarm's identity, and that a handler may undo
 * its own alarm from inside `onFire` without leaving anything armed or occupied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerUpgradeContractAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val savedLocale: Locale = Locale.getDefault()
    private val savedZone: TimeZone = TimeZone.getDefault()

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String = "t", type: String = "T", at: Long = 1_700_000_000_000L): ScheduledAlarm =
        ScheduledAlarm(id, type, at, AlarmNotification(title, "body", "chan"), mapOf("k" to "v"))

    private fun armed(): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms.map { scheduled ->
        AlarmIntents.fromIntent(
            shadowOf(scheduled.operation).savedIntent,
            AlarmIntentKeys(SCHEME_ANY, "alarm_id", "alarm_type"),
        )!!
    }

    private fun occupiedSlots(id: String, scheme: String): Int {
        val keys = AlarmIntentKeys(scheme, "alarm_id", "alarm_type")
        return (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
            PendingIntent.getBroadcast(
                context,
                AlarmIntents.requestCode(id, slot),
                AlarmIntents.cancelIntent(context, id, keys),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) != null
        }
    }

    private fun recordingHandler(sink: MutableList<ScheduledAlarm>): AlarmHandler = object : AlarmHandler {
        override val type: String = "T"
        override suspend fun onFire(alarm: ScheduledAlarm) { sink += alarm }
    }

    private fun fireAll(wait: CountDownLatch) {
        val receiver = AlarmReceiver()
        shadowAlarmManager.scheduledAlarms.toList().forEach { receiver.onReceive(context, shadowOf(it.operation).savedIntent) }
        assertTrue(wait.await(10, TimeUnit.SECONDS), "not every alarm was delivered")
    }

    @Test
    fun `an alarm armed under an earlier scheme stays armed, cannot be cancelled or replaced, and is still delivered, exactly as documented`() {
        val delivered: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val before = createAlarmScheduler(context, listOf(recordingHandler(delivered)), AlarmSchedulerConfig(alarmIntentScheme = "scheme-old"))
        runBlocking { before.schedule(alarm("x", title = "old")) }
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)

        val after = createAlarmScheduler(context, listOf(recordingHandler(delivered)), AlarmSchedulerConfig(alarmIntentScheme = "scheme-new"))
        runBlocking { after.cancel("x") }
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "cancel under the new scheme must be the documented silent no-op")
        assertEquals(1, occupiedSlots("x", "scheme-old"))

        runBlocking { after.schedule(alarm("x", title = "new")) }
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size, "scheduling the same id under the new scheme arms a second alarm")

        // The orphan is not hidden from the receiver: both arrive, with their own content.
        val latch = CountDownLatch(2)
        val counting = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { delivered += alarm; latch.countDown() }
        }
        createAlarmScheduler(context, listOf(counting), AlarmSchedulerConfig(alarmIntentScheme = "scheme-new"))
        delivered.clear()
        fireAll(latch)
        assertEquals(setOf("old", "new"), delivered.map { it.notification.title }.toSet())

        // And it is recoverable: going back to the old scheme retires it.
        runBlocking { before.cancel("x") }
        assertEquals(listOf("new"), armed().map { it.notification.title })
        assertEquals(0, occupiedSlots("x", "scheme-old"))
        runBlocking { after.cancel("x") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, occupiedSlots("x", "scheme-new"))
    }

    @Test
    fun `an alarm armed under an earlier id key is dropped without a trace when it fires, but is still cancelled and replaced by id`() {
        val delivered: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val oldConfig = AlarmSchedulerConfig(alarmIdKey = "id_v1")
        val newConfig = AlarmSchedulerConfig(alarmIdKey = "id_v2", alarmTypeKey = "type_v2")
        val v1 = createAlarmScheduler(context, listOf(recordingHandler(delivered)), oldConfig)
        runBlocking { v1.schedule(alarm("x", title = "armed-by-v1")) }
        runBlocking { v1.schedule(alarm("y", title = "armed-by-v1")) }

        val v2 = createAlarmScheduler(context, listOf(recordingHandler(delivered)), newConfig)
        // The orphan reaches the receiver and is dropped, and does not poison the next alarm.
        val orphan = shadowAlarmManager.scheduledAlarms.first()
        AlarmReceiver().onReceive(context, shadowOf(orphan.operation).savedIntent)
        runBlocking { v2.schedule(alarm("z", title = "armed-by-v2")) }
        val next = shadowAlarmManager.scheduledAlarms.last()
        val latch = CountDownLatch(1)
        createAlarmScheduler(context, listOf(object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { delivered += alarm; latch.countDown() }
        }), newConfig)
        AlarmReceiver().onReceive(context, shadowOf(next.operation).savedIntent)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("armed-by-v2"), delivered.map { it.notification.title }, "the v1 alarm was not dropped")

        // The key is not part of the PendingIntent identity: the scheme is, and it did not change.
        runBlocking { v2.schedule(alarm("x", title = "replaced-by-v2")) }
        runBlocking { v2.cancel("y") }
        val titles: List<String> = shadowAlarmManager.scheduledAlarms.map { shadowOf(it.operation).savedIntent.getStringExtra("kmptoolkit_alarm_title")!! }
        assertEquals(setOf("armed-by-v2", "replaced-by-v2"), titles.toSet())
        assertEquals(2, titles.size)
        runBlocking { v2.cancelAll(listOf("x", "z")) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        listOf("x", "y", "z").forEach { id: String -> assertEquals(0, occupiedSlots(id, newConfig.resolveIntentScheme(context.packageName)), id) }
    }

    @Test
    fun `locale, time zone and DST never take part in an alarm's identity or time`() {
        val ids: List<String> = listOf("plain", "\uD800", "a\uDC00b", "😀\uD800", "id-\uDFFF-end", "ÀÉ ünï")
        val at = 1_711_846_800_000L // an instant next to a DST change in several zones
        Locale.setDefault(Locale.ROOT)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        runBlocking { createAlarmScheduler(context).also { scheduler -> ids.forEach { scheduler.schedule(alarm(it, at = at)) } } }
        assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size)
        val locales: List<Locale> = listOf(
            Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.forLanguageTag("hi-IN-u-nu-deva"),
            Locale.forLanguageTag("th-TH-u-nu-thai"), Locale.forLanguageTag("fa-IR"), Locale.forLanguageTag("lt-LT"),
        )
        val zones: List<String> = listOf("Pacific/Kiritimati", "America/St_Johns", "Europe/Istanbul", "Australia/Lord_Howe", "America/Sao_Paulo", "Pacific/Apia")
        locales.forEachIndexed { index: Int, locale: Locale ->
            Locale.setDefault(locale)
            TimeZone.setDefault(TimeZone.getTimeZone(zones[index]))
            // A new process after the user changed language and zone: the same ids must be found and replaced.
            val restarted: AlarmScheduler = createAlarmScheduler(context)
            ids.forEach { id: String -> runBlocking { restarted.schedule(alarm(id, title = "round-$index", at = at)) } }
            assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size, "an id was not found again under $locale / ${zones[index]}")
            assertEquals(setOf(at), shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet(), "the instant moved under ${zones[index]}")
            assertEquals(setOf("round-$index"), armed().map { it.notification.title }.toSet())
        }
        Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
        runBlocking { createAlarmScheduler(context).cancelAll(ids) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        ids.forEach { id: String ->
            assertEquals(0, occupiedSlots(id, AlarmSchedulerConfig().resolveIntentScheme(context.packageName)), "slot left for '$id'")
        }
    }

    @Test
    fun `a handler that cancels the alarm it was fired for and a neighbour leaves nothing armed or occupied for them`() {
        val done = CountDownLatch(1)
        lateinit var scheduler: AlarmScheduler
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                scheduler.cancel(alarm.id)
                scheduler.cancel("b")
                done.countDown()
            }
        }
        scheduler = createAlarmScheduler(context, handlerProvider = { listOf(handler) })
        listOf("a", "b", "c").forEach { id: String -> runBlocking { scheduler.schedule(alarm(id, at = 5L)) } }
        val scheme: String = AlarmSchedulerConfig().resolveIntentScheme(context.packageName)

        val fired = shadowAlarmManager.scheduledAlarms.first { shadowOf(it.operation).savedIntent.getStringExtra("alarm_id") == "a" }
        AlarmReceiver().onReceive(context, shadowOf(fired.operation).savedIntent)
        assertTrue(done.await(10, TimeUnit.SECONDS), "the handler never finished (the lock held across onFire?)")

        assertEquals(listOf("c"), armed().map { it.id })
        assertEquals(0, occupiedSlots("a", scheme))
        assertEquals(0, occupiedSlots("b", scheme))
        assertEquals(1, occupiedSlots("c", scheme))
    }

    @Test
    fun `an id of nothing but unicode spaces is blank and rejected, one with a zero width character is a real id that round trips`() {
        listOf(" ", " ", "　", "\t\n", "  ").forEach { blank: String ->
            assertFailsWith<IllegalArgumentException>("'${blank.map { it.code }}' must be blank") { alarm(blank) }
        }
        val id = "​"
        val delivered: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val latch = CountDownLatch(1)
        val scheduler = createAlarmScheduler(context, listOf(object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { delivered += alarm; latch.countDown() }
        }))
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(id)) })
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("‌")) })
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)
        runBlocking { scheduler.cancel("‌") }
        fireAll(latch)
        assertEquals(listOf(id), delivered.map { it.id })
        runBlocking { scheduler.cancel(id) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    private companion object {
        const val SCHEME_ANY: String = "any"
    }
}
