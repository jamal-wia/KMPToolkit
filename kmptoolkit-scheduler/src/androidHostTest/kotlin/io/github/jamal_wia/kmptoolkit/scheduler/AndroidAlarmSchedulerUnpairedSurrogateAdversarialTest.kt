package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Ids are arbitrary non-blank Strings, "two ids never collapse into one". The per-id data URI percent-encodes
 * the id as UTF-8, which turns every unpaired surrogate into '?', so ids that differ only in unpaired
 * surrogates share the URI; with equal hash codes they also share every request code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerUnpairedSurrogateAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    private fun armedTitles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { requireNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }
        .sorted()

    @Test
    fun `the two ids really are different strings with equal hash codes`() {
        assertEquals(A.hashCode(), B.hashCode())
        assertEquals(false, A == B)
    }

    @Test
    fun `ids that differ only in unpaired surrogates are two alarms`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm(A, "a"))
            scheduler.schedule(alarm(B, "b"))
        }
        assertEquals(listOf("a", "b"), armedTitles())
    }

    @Test
    fun `cancelling one of two ids that differ only in unpaired surrogates leaves the other armed`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm(A, "a"))
            scheduler.schedule(alarm(B, "b"))
            scheduler.cancel(A)
        }
        assertEquals(listOf("b"), armedTitles())
    }

    private companion object {
        // Every character is an unpaired high surrogate; 0xD801 * 31 + 0xD800 == 0xD800 * 31 + 0xD81F.
        const val A: String = "\uD801\uD800"
        const val B: String = "\uD800\uD81F"
    }
}
