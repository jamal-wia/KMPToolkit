package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import java.io.File
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
 * Round 15 (contract lawyer): sentences of the scheduler overview and platform notes that say what the
 * module does NOT do.
 *
 * - 01-overview, "Not an uploader": "It carries no queue, no delivery guarantee, and no persistence ...
 *   nothing is recorded." 04-api-reference: "Stateless: it remembers nothing, persists nothing".
 * - 01-overview, "Not a notification library": "It creates no channels, requests no permissions, shows no
 *   system prompt, and renders nothing on Android".
 * - 05-platform-notes, "Reboot": "stores nothing that would let it" (re-arm).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSchedulerCreatesAndPersistsNothingAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private fun appFiles(): Map<String, Long> {
        val root = File(context.applicationInfo.dataDir)
        val roots: List<File> = listOfNotNull(root, context.getExternalFilesDir(null))
        return roots.flatMap { dir: File ->
            dir.walkTopDown().filter { it.isFile }.map { file: File -> file.absolutePath to file.length() }.toList()
        }.toMap()
    }

    private fun alarm(id: String, type: String, fireAt: Long): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = type,
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = "title $id", body = "body $id", channelId = "channel-$id"),
        payload = mapOf("lesson" to id, "k" to "v".repeat(50)),
    )

    @Test
    fun `a full life of alarms - schedule replace fire cancel - writes no file and no preference`() {
        val before: Map<String, Long> = appFiles()
        val fired = CountDownLatch(3)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                fired.countDown()
            }
        }
        val scheduler: AlarmScheduler = createAlarmScheduler(context, listOf(handler), AlarmSchedulerConfig(alarmIntentScheme = "r15"))

        runBlocking {
            for (i in 0 until 60) scheduler.schedule(alarm("id-$i", "T", 1_900_000_000_000L + i))
            for (i in 0 until 60 step 2) scheduler.schedule(alarm("id-$i", "T", 1_950_000_000_000L + i))
        }
        shadowAlarmManager.scheduledAlarms.take(3).forEach { scheduled ->
            @Suppress("DEPRECATION")
            AlarmReceiver().onReceive(context, shadowOf(requireNotNull(scheduled.operation)).savedIntent)
        }
        assertTrue(fired.await(10, TimeUnit.SECONDS), "the handler never ran")
        runBlocking {
            scheduler.cancelAll((0 until 30).map { "id-$it" })
            scheduler.cancel("id-59")
        }

        assertEquals(before, appFiles(), "the scheduler wrote to the app's data directory: it persists nothing")
        assertTrue(
            context.getSharedPreferences("any", Context.MODE_PRIVATE).all.isEmpty(),
            "no shared preference may exist for a module that persists nothing",
        )
    }

    @Test
    fun `the scheduler creates no notification channel and renders no notification fired or not`() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadow = shadowOf(notificationManager)
        val channelsBefore: Int = shadow.notificationChannels.size
        val fired = CountDownLatch(1)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                fired.countDown()
            }
        }
        val scheduler: AlarmScheduler = createAlarmScheduler(context, listOf(handler))

        runBlocking { scheduler.schedule(alarm("a", "T", 1_900_000_000_000L)) }
        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(context, shadowOf(requireNotNull(shadowAlarmManager.nextScheduledAlarm?.operation)).savedIntent)
        assertTrue(fired.await(10, TimeUnit.SECONDS), "the handler never ran")
        runBlocking { scheduler.cancel("a") }

        assertEquals(channelsBefore, shadow.notificationChannels.size, "the scheduler created a notification channel")
        assertEquals(0, shadow.allNotifications.size, "the scheduler rendered a notification on Android")
    }
}
