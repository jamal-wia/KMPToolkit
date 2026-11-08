package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * Look-alike ids (lone surrogates, '?', percent forms, pairs) through the whole lifecycle at once: schedule,
 * replace, cancel, process death (a new scheduler on the same AlarmManager and the dispatch registration lost),
 * refused cancels, refused sets and saturated ids. The model is the documented contract: four slots per id,
 * a refused retirement leaves its alarm armed, a saturated id answers Failed and changes nothing until cancel(id).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 31, 34], shadows = [AndroidAlarmSchedulerLookAlikeChaosAdversarialTest.ModeShadowAlarmManager::class])
class AndroidAlarmSchedulerLookAlikeChaosAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true); ModeShadowAlarmManager.mode.set(Mode.HEALTHY) }

    @After
    fun tearDown() { ModeShadowAlarmManager.mode.set(Mode.HEALTHY); AlarmDispatch.reset() }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    private fun armed(): List<Pair<String, String>> = shadowAlarmManager.scheduledAlarms.map {
        val a = assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys))
        a.id to a.notification.title
    }.sortedWith(compareBy({ it.first }, { it.second }))

    @Test
    fun `every look-alike id is its own alarm and cancels alone, across scheduler restarts`() {
        for (seed in 0 until 40) {
            val random = Random(seed)
            val ids = LOOK_ALIKES.shuffled(random)
            var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            runBlocking {
                ids.forEachIndexed { index, id ->
                    if (random.nextInt(4) == 0) scheduler = AndroidAlarmScheduler(context, keys)
                    assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "t$index")))
                }
            }
            assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size, "seed $seed: ids merged")
            val remaining = ids.toMutableList()
            for (id in ids.shuffled(random)) {
                if (random.nextInt(3) == 0) scheduler = AndroidAlarmScheduler(context, keys)
                runBlocking { scheduler.cancel(id) }
                remaining.remove(id)
                assertEquals(remaining.sorted(), armed().map { it.first }.sorted(), "seed $seed: cancel of ${id.map { it.code }}")
            }
        }
    }

    @Test
    fun `saturated look-alike ids stay failed across a restart without touching their neighbours, cancel recovers`() {
        for (saturated in LOOK_ALIKES) {
            val others: List<String> = LOOK_ALIKES.filter { it != saturated }
            var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            runBlocking {
                others.forEach { scheduler.schedule(alarm(it, "other")) }
                scheduler.schedule(alarm(saturated, "s1"))
                ModeShadowAlarmManager.mode.set(Mode.REFUSE_CANCEL)
                for (n in 2..4) assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(saturated, "s$n")))
                ModeShadowAlarmManager.mode.set(Mode.HEALTHY)
            }
            val before = armed()
            AlarmDispatch.reset()
            scheduler = AndroidAlarmScheduler(context, keys)
            runBlocking {
                assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm(saturated, "s5")), "saturated ${saturated.map { it.code }}")
                assertEquals(before, armed(), "a Failed schedule of a saturated id changed something")
                // a neighbour is unaffected: replace it
                val neighbour = others.first()
                assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(neighbour, "other2")))
                scheduler.cancel(saturated)
                assertEquals(others.sorted(), armed().map { it.first }.sorted(), "cancel(saturated) left the id's alarms or removed a neighbour's")
                assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(saturated, "fresh")))
                assertEquals(1, armed().count { it.first == saturated })
                scheduler.cancelAll(LOOK_ALIKES)
            }
            assertEquals(emptyList(), armed())
        }
    }

    @Test
    fun `random lifecycle over look-alike ids matches the four slot model`() {
        for (seed in 0 until 150) {
            val random = Random(seed)
            val ids = LOOK_ALIKES.shuffled(random).take(5)
            // per id: titles of alarms still armed, in slot order (slot count = size)
            val slots: MutableMap<String, MutableList<String>> = mutableMapOf()
            var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val log = StringBuilder()
            repeat(40) { step ->
                val id = ids[random.nextInt(ids.size)]
                val mood = Mode.values()[random.nextInt(Mode.values().size)]
                ModeShadowAlarmManager.mode.set(mood)
                if (random.nextInt(8) == 0) { AlarmDispatch.reset(); scheduler = AndroidAlarmScheduler(context, keys) }
                val held = slots.getOrPut(id) { mutableListOf() }
                if (random.nextInt(4) == 0) {
                    log.append("cancel($mood) ")
                    runBlocking { scheduler.cancel(id) }
                    if (mood != Mode.REFUSE_CANCEL) held.clear()
                } else {
                    val title = "t$step"
                    log.append("schedule($mood,$title) ")
                    val result = runBlocking { scheduler.schedule(alarm(id, title)) }
                    when {
                        held.size >= 4 -> assertIs<AlarmScheduleResult.Failed>(result, "seed $seed: saturated id must fail; $log")
                        mood == Mode.REFUSE_SET -> assertIs<AlarmScheduleResult.Failed>(result, "seed $seed: $log")
                        else -> {
                            assertIs<AlarmScheduleResult.Exact>(result, "seed $seed: $log")
                            if (mood == Mode.REFUSE_CANCEL) held.add(title) else { held.clear(); held.add(title) }
                        }
                    }
                }
                val expected = slots.flatMap { (i, titles) -> titles.map { i to it } }.sortedWith(compareBy({ it.first }, { it.second }))
                ModeShadowAlarmManager.mode.set(Mode.HEALTHY)
                assertEquals(expected, armed(), "seed $seed step $step: $log")
            }
            runBlocking { scheduler.cancelAll(ids) }
            ModeShadowAlarmManager.mode.set(Mode.HEALTHY)
            runBlocking { scheduler.cancelAll(ids) }
            assertEquals(emptyList(), armed(), "seed $seed: cancelAll after healthy mood left alarms")
        }
    }

    enum class Mode { HEALTHY, REFUSE_SET, REFUSE_CANCEL }

    @Implements(AlarmManager::class)
    class ModeShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (mode.get() == Mode.REFUSE_SET) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (mode.get() == Mode.REFUSE_CANCEL && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val mode: AtomicReference<Mode> = AtomicReference(Mode.HEALTHY)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }

    private companion object {
        val LOOK_ALIKES: List<String> = listOf(
            "\uD800", "?", "%3F", "%uD800", "%25uD800", "\uD800\uD800", "%uD800%uD800", "\uDC00", "%uDC00",
            "𐀀", "%F0%90%80%80", "😀", "\uD83D", "\uDE00", "\uD83D😀", "😀\uDE00",
            "\uD801\uD800", "\uD800\uD81F",
        )
    }
}
