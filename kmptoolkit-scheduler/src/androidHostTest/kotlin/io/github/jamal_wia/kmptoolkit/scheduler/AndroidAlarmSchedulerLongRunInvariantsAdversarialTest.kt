package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Long random runs (40 operations, four ids, five platform moods including an exact alarm revoked mid-call
 * followed by a refused inexact one) against the four-slot scheme. Invariants that must hold at every step:
 * a healthy schedule leaves exactly its own alarm and one slot, a healthy cancel leaves nothing, an id never
 * holds more alarms than slots, a refused call changes nothing for a not-saturated id, and no call touches
 * another id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerLongRunInvariantsAdversarialTest.ModalShadowAlarmManager::class])
open class AndroidAlarmSchedulerLongRunInvariantsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
    }

    @After
    fun reset() {
        ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
        AlarmDispatch.reset()
    }

    private fun titles(id: String): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)) }
        .filter { it.id == id }.map { it.notification.title }.sorted()

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `invariants hold through long random runs of refusals and recoveries`() {
        val ids: List<String> = listOf("a", "b", "Aa", "BB")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val log = StringBuilder()
            try {
                repeat(STEPS) { step: Int ->
                    val id: String = ids[random.nextInt(ids.size)]
                    val mode: Mode = if (random.nextInt(3) == 0) Mode.HEALTHY else Mode.values()[random.nextInt(Mode.values().size)]
                    ModalShadowAlarmManager.mode.set(mode)
                    val othersBefore: Map<String, List<String>> = ids.filter { it != id }.associateWith { titles(it) }
                    val slotsBefore: Int = slots(id)
                    val titlesBefore: List<String> = titles(id)
                    if (random.nextInt(4) == 0) {
                        runBlocking { scheduler.cancel(id) }
                        log.append("C($id,$mode) ")
                        if (mode == Mode.HEALTHY) {
                            assertEquals(emptyList(), titles(id), "seed $seed: healthy cancel left alarms. $log")
                            assertEquals(0, slots(id), "seed $seed: healthy cancel left slots. $log")
                        }
                    } else {
                        val title = "s$seed-$step"
                        val result: AlarmScheduleResult = runBlocking {
                            scheduler.schedule(
                                ScheduledAlarm(
                                    id = id, type = "T", fireAtEpochMillis = 1_000L + step,
                                    notification = AlarmNotification(title = title, body = "b", channelId = "c"),
                                ),
                            )
                        }
                        log.append("S($id,$mode,${result::class.simpleName}) ")
                        val now: List<String> = titles(id)
                        if (result is AlarmScheduleResult.Failed) {
                            assertTrue(title !in now || slotsBefore >= AlarmIntents.SLOT_COUNT, "seed $seed: Failed but armed. $log")
                            if (slotsBefore < AlarmIntents.SLOT_COUNT) {
                                assertEquals(titlesBefore, now, "seed $seed: a refused schedule changed the alarms. $log")
                                assertEquals(slotsBefore, slots(id), "seed $seed: a refused schedule changed the slots. $log")
                            }
                        } else {
                            assertEquals(1, now.count { it == title }, "seed $seed: the new alarm is not armed exactly once. $log")
                            if (mode == Mode.HEALTHY) {
                                assertEquals(listOf(title), now, "seed $seed: healthy schedule left stale alarms. $log")
                                assertEquals(1, slots(id), "seed $seed: healthy schedule left stale slots. $log")
                            }
                        }
                    }
                    assertTrue(titles(id).size <= AlarmIntents.SLOT_COUNT, "seed $seed: more alarms than slots. $log")
                    assertEquals(othersBefore, ids.filter { it != id }.associateWith { titles(it) }, "seed $seed: another id changed. $log")
                }
            } finally {
                ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
                runBlocking { scheduler.cancelAll(ids) }
            }
            ids.forEach { assertEquals(0, slots(it), "seed $seed: leftover slots for $it after a healthy cancelAll") }
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "seed $seed: leftover alarms after a healthy cancelAll")
        }
    }

    enum class Mode { HEALTHY, REFUSE_SET, REFUSE_CANCEL, EXACT_REVOKED, EXACT_REVOKED_THEN_REFUSED }

    @Implements(AlarmManager::class)
    class ModalShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            when (mode.get()) {
                Mode.REFUSE_SET -> throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
                Mode.EXACT_REVOKED, Mode.EXACT_REVOKED_THEN_REFUSED -> throw SecurityException("exact alarms revoked")
                else -> Unit
            }
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (mode.get() == Mode.EXACT_REVOKED_THEN_REFUSED) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            insideSet.set(true)
            try { super.setAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
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
        const val SEEDS: Int = 120
        const val STEPS: Int = 40
    }
}

/** The same runs on the oldest supported level (no exact-alarm permission check, no immutable-flag quirks). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], shadows = [AndroidAlarmSchedulerLongRunInvariantsAdversarialTest.ModalShadowAlarmManager::class])
class AndroidAlarmSchedulerLongRunInvariantsApi24AdversarialTest : AndroidAlarmSchedulerLongRunInvariantsAdversarialTest()

/** The same runs where exact alarms need a permission the test grants and revokes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], shadows = [AndroidAlarmSchedulerLongRunInvariantsAdversarialTest.ModalShadowAlarmManager::class])
class AndroidAlarmSchedulerLongRunInvariantsApi31AdversarialTest : AndroidAlarmSchedulerLongRunInvariantsAdversarialTest()
