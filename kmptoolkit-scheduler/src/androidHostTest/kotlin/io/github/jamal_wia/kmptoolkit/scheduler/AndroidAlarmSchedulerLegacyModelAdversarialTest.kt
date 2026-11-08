package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
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
 * A reference model of the four-slot scheme extended by the 3.0.x legacy lookup (D-65), written from docs 05
 * alone: alarms planted the way 3.0.x armed them (plain `appendPath`, request code = hash code) for six ids
 * (lone surrogates, equal hash codes and equal lossy URIs, a literal `?` look-alike, an ordinary id), then random
 * schedule / cancel / restart steps under random moods (healthy, platform refuses set, platform refuses cancel,
 * exact-alarm permission missing). The set of armed alarms is compared with the model after every step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerLegacyModelAdversarialTest.MoodShadow::class])
class AndroidAlarmSchedulerLegacyModelAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        MoodShadow.refuseSet.set(false)
        MoodShadow.refuseCancel.set(false)
        MoodShadow.revokedExact.set(false)
    }

    @After
    fun tearDown() {
        MoodShadow.refuseSet.set(false)
        MoodShadow.refuseCancel.set(false)
        MoodShadow.revokedExact.set(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    private fun plantAsEarlierVersion(id: String, title: String) {
        val intent = AlarmIntents.toIntent(context, alarm(id, title), keys)
        intent.data = Uri.Builder().scheme(keys.intentScheme).appendPath(id).build()
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1_000L, pending)
    }

    private fun armed(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }.sorted()

    private fun String.hasLone(): Boolean = this != lossless()

    /** The id with every unpaired surrogate replaced by '?'-free marker, equal to itself when none is unpaired. */
    private fun String.lossless(): String {
        val out = StringBuilder()
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) { out.append(c).append(this[i + 1]); i += 2 }
            else if (c.isSurrogate()) { out.append('#'); i++ } else { out.append(c); i++ }
        }
        return out.toString()
    }

    private fun String.lossy(): String {
        val out = StringBuilder()
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) { out.append(c).append(this[i + 1]); i += 2 }
            else if (c.isSurrogate()) { out.append('?'); i++ } else { out.append(c); i++ }
        }
        return out.toString()
    }

    private class IdModel { val slots: MutableMap<Int, String> = sortedMapOf() }

    @Test
    fun `random lifecycles with planted 3 0 x alarms match the four slot and legacy model`() {
        val ids = listOf("task\uD800", "task\uD801", "task?", "\uD800\uD820", "\uD801\uD801", "ok")
        // sanity of the fixture: the equal-hash pair really has one hash code and one lossy URI
        assertEquals("\uD800\uD820".hashCode(), "\uD801\uD801".hashCode())
        assertEquals("\uD800\uD820".lossy(), "\uD801\uD801".lossy())
        for (seed in 0 until 120) {
            val random = Random(seed)
            val models: Map<String, IdModel> = ids.associateWith { IdModel() }
            val legacy: MutableMap<String, String> = mutableMapOf() // legacy key -> title
            fun legacyKey(id: String) = id.lossy() + "#" + id.hashCode()
            var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val log = StringBuilder()
            var counter = 0
            // plant
            for (id in ids) if (random.nextInt(2) == 0) {
                val title = "p${counter++}"
                plantAsEarlierVersion(id, title)
                if (id.hasLone()) legacy[legacyKey(id)] = title else models.getValue(id).slots[0] = title
                log.append("plant($id,$title) ")
            }
            fun expected(): List<String> = (models.values.flatMap { it.slots.values } + legacy.values).sorted()
            assertEquals(expected(), armed(), "seed $seed after planting: $log")
            repeat(30) { step ->
                val id = ids[random.nextInt(ids.size)]
                val model = models.getValue(id)
                val refuseSet = random.nextInt(6) == 0
                val refuseCancel = random.nextInt(3) == 0
                val exact = random.nextInt(5) != 0
                val revoked = random.nextInt(6) == 0
                MoodShadow.refuseSet.set(refuseSet); MoodShadow.refuseCancel.set(refuseCancel); MoodShadow.revokedExact.set(revoked)
                ShadowAlarmManager.setCanScheduleExactAlarms(exact)
                if (random.nextInt(8) == 0) { AlarmDispatch.reset(); scheduler = AndroidAlarmScheduler(context, keys); log.append("restart ") }
                val flags = "set=${if (refuseSet) "R" else "ok"} cancel=${if (refuseCancel) "R" else "ok"} exact=$exact revoked=$revoked"
                if (random.nextInt(4) == 0) {
                    log.append("cancel($id,$flags) ")
                    runBlocking { scheduler.cancel(id) }
                    if (!refuseCancel) {
                        model.slots.clear()
                        if (id.hasLone()) legacy.remove(legacyKey(id))
                    }
                } else {
                    val title = "t${counter++}"
                    log.append("schedule($id,$title,$flags) ")
                    val result = runBlocking { scheduler.schedule(alarm(id, title)) }
                    val saturated = model.slots.size >= 4
                    if (saturated || refuseSet) {
                        assertIs<AlarmScheduleResult.Failed>(result, "seed $seed step $step: $log")
                    } else {
                        if (exact && !revoked) assertIs<AlarmScheduleResult.Exact>(result, "seed $seed step $step: $log")
                        else assertIs<AlarmScheduleResult.Inexact>(result, "seed $seed step $step: $log")
                        val target = (0..3).first { it !in model.slots }
                        model.slots[target] = title
                        if (!refuseCancel) {
                            model.slots.keys.retainAll(setOf(target))
                            if (id.hasLone()) legacy.remove(legacyKey(id))
                        }
                    }
                }
                MoodShadow.refuseSet.set(false); MoodShadow.refuseCancel.set(false); MoodShadow.revokedExact.set(false)
                ShadowAlarmManager.setCanScheduleExactAlarms(true)
                assertEquals(expected(), armed(), "seed $seed step $step: $log")
            }
            runBlocking { scheduler.cancelAll(ids) }
            assertEquals(emptyList(), armed(), "seed $seed: cancelAll in a healthy mood left alarms; $log")
        }
    }

    @Implements(AlarmManager::class)
    class MoodShadow : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (refuseSet.get()) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            if (revokedExact.get()) throw SecurityException("exact alarms revoked")
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (refuseSet.get()) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            insideSet.set(true)
            try { super.setAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (refuseCancel.get() && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val refuseSet: AtomicReference<Boolean> = AtomicReference(false)
            val refuseCancel: AtomicReference<Boolean> = AtomicReference(false)
            val revokedExact: AtomicReference<Boolean> = AtomicReference(false)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }
}
