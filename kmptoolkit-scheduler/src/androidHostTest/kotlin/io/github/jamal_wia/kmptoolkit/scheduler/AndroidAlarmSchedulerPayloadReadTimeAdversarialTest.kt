package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * `docs/kmptoolkit-scheduler/06-testing.md`: the real schedulers "write the payload into the platform
 * request at the call". The Android scheduler serialises every `schedule` behind one process-wide lock,
 * so a payload map the caller edits or reuses while its call is queued behind another one must be
 * armed as it was when `schedule` was called, not as it was when the lock was won. The lock is held
 * by a call whose context blocks inside the lock ([LockHoldingContext]).
 *
 * The equivalence audit pins the same property the recording double was fixed to have: what the
 * double records at the call is what the real scheduler must arm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerPayloadReadTimeAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val holderContext = LockHoldingContext(context, "kmptoolkit-adversarial-holder", CountDownLatch(1), CountDownLatch(1))
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, payload: Map<String, String>): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 5L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
        payload = payload,
    )

    private fun armedPayloadOf(id: String): Map<String, String>? = shadowAlarmManager.scheduledAlarms
        .mapNotNull { AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys) }
        .singleOrNull { it.id == id }?.payload

    private fun queuedBehindSlowSchedule(mutate: (MutableMap<String, String>) -> Unit) {
        val scheduler: AlarmScheduler = createAlarmScheduler(holderContext)
        val entered: CountDownLatch = holderContext.entered
        val release: CountDownLatch = holderContext.release
        val holder: Thread = thread(name = "kmptoolkit-adversarial-holder") {
            runBlocking { scheduler.schedule(alarm("holder", emptyMap())) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the first schedule never reached the platform")
            val payload: MutableMap<String, String> = mutableMapOf("k" to "v1")
            runBlocking {
                withTimeout(10_000L) {
                    val queued = launch(start = CoroutineStart.UNDISPATCHED) { scheduler.schedule(alarm("queued", payload)) }
                    mutate(payload)
                    release.countDown()
                    queued.join()
                }
            }
        } finally {
            release.countDown()
            holder.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    @Test
    fun `a payload map edited while schedule waits for the lock is armed as it was when schedule was called`() {
        queuedBehindSlowSchedule { it["k"] = "v2" }

        assertEquals(mapOf("k" to "v1"), armedPayloadOf("queued"))
    }

    @Test
    fun `a payload map that gains a key while schedule waits for the lock does not arm the new key`() {
        queuedBehindSlowSchedule { it["extra"] = "x" }

        assertEquals(mapOf("k" to "v1"), armedPayloadOf("queued"))
    }

    @Test
    fun `control - an untouched payload queued behind a slow schedule is armed whole`() {
        queuedBehindSlowSchedule { }

        assertEquals(mapOf("k" to "v1"), armedPayloadOf("queued"))
    }
}
