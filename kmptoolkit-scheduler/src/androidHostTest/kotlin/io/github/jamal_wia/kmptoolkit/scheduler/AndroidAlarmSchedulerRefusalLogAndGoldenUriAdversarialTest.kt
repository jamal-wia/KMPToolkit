package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog

/**
 * Mutation survivor hunt (round 13).
 * (1) Docs 05-platform-notes: a platform refusal of a cancel is LOGGED (warning, tag `KmpToolkitScheduler`, naming the
 * alarm id) and the alarm may stay armed; the same holds for the retirement of the previous alarm during a replacement.
 * (2) The request-identity URI of an alarm whose id has an unpaired surrogate keeps the lossless form shipped since
 * 4.0.0 (`%uXXXX`, upper-case hex, four digits): an alarm armed by 4.0.0 has to be found and cancelled by this version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerRefusalLogAndGoldenUriAdversarialTest.RefusingCancelShadow::class])
class AndroidAlarmSchedulerRefusalLogAndGoldenUriAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        RefusingCancelShadow.refuseCode.set(null)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        RefusingCancelShadow.refuseCode.set(null)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    private fun warnings(): List<String> = ShadowLog.getLogs()
        .filter { it.tag == "KmpToolkitScheduler" && it.type == Log.WARN }
        .map { it.msg }

    @Test
    fun `a refused cancel is logged with the alarm id`() = runBlocking {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        scheduler.schedule(alarm("refused-cancel"))
        RefusingCancelShadow.refuseCode.set(AlarmIntents.requestCode("refused-cancel", 0))
        scheduler.cancel("refused-cancel")
        assertTrue(warnings().any { "refused-cancel" in it }, "no warning for the refused cancel: ${warnings()}")
    }

    @Test
    fun `a refused retirement during a replacement is logged with the alarm id`() = runBlocking {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        scheduler.schedule(alarm("refused-retire"))
        // Only the first slot's intent is refused: the shadow itself cancels the operation it is asked to arm
        // (the replacement lives in slot 1), and that internal call must not be what fails.
        RefusingCancelShadow.refuseCode.set(AlarmIntents.requestCode("refused-retire", 0))
        scheduler.schedule(alarm("refused-retire"))
        assertTrue(warnings().any { "refused-retire" in it }, "no warning for the refused retirement: ${warnings()}")
    }

    @Test
    fun `the lossless identity of an id with unpaired surrogates is the 4_0_0 form`() {
        val expected: Map<String, String> = mapOf(
            "task\uD800" to "task%uD800",
            "a\uDC00b" to "a%uDC00b",
            "\uD800" to "%uD800",
            "😀\uD800" to "%F0%9F%98%80%uD800",
            "x􏿿y\uDFFF" to "x%F4%8F%BF%BFy%uDFFF",
        )
        for ((id: String, path: String) in expected) {
            val encoded: String = checkNotNull(AlarmIntents.cancelIntent(context, id, keys).data).encodedPath.orEmpty()
            assertEquals("/$path", encoded, "identity path of ${id.map { it.code.toString(16) }}")
        }
    }

    @Implements(AlarmManager::class)
    class RefusingCancelShadow : ShadowAlarmManager() {

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (operation != null && shadowOf(operation).requestCode == refuseCode.get()) throw SecurityException("cancel refused")
            super.cancel(operation)
        }

        companion object {
            val refuseCode: AtomicReference<Int?> = AtomicReference(null)
        }
    }
}
