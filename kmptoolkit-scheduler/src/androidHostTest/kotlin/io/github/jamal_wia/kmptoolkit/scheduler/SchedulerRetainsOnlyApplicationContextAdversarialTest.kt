package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * `createAlarmScheduler` KDoc: "The context is not retained: only its application context is, so
 * passing an `Activity` here leaks nothing."
 *
 * The context handed in plays an activity destroyed right after `createAlarmScheduler` returned:
 * any later use of it throws. Scheduling, replacing, cancelling and a fired alarm reaching its
 * handler must all keep working from the application context alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulerRetainsOnlyApplicationContextAdversarialTest {

    private val app: Context = RuntimeEnvironment.getApplication()

    private class DestroyedActivity(private val application: Context) : ContextWrapper(application) {
        @Volatile var destroyed: Boolean = false

        private fun alive(what: String) {
            check(!destroyed) { "the activity context was used after it was destroyed: $what" }
        }

        override fun getApplicationContext(): Context = application
        override fun getSystemService(name: String): Any? { alive("getSystemService"); return super.getSystemService(name) }
        override fun getPackageName(): String { alive("getPackageName"); return super.getPackageName() }
        override fun getPackageManager(): android.content.pm.PackageManager { alive("getPackageManager"); return super.getPackageManager() }
        override fun getResources(): android.content.res.Resources { alive("getResources"); return super.getResources() }
    }

    @Before
    fun grantExactAlarms() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    @Test
    fun `a scheduler built from an activity keeps working after the activity is destroyed`() {
        val fired = CountDownLatch(1)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { fired.countDown() }
        }
        val activity = DestroyedActivity(app)
        val scheduler: AlarmScheduler = createAlarmScheduler(activity, handlers = listOf(handler))
        activity.destroyed = true
        val alarm = ScheduledAlarm("a", "T", 1_000L, AlarmNotification("t", "b", "c"))
        val manager: AlarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        runBlocking {
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm))
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm.copy(fireAtEpochMillis = 2_000L)))
        }
        val armed = assertNotNull(shadowOf(manager).nextScheduledAlarm)
        assertEquals(2_000L, armed.triggerAtTime)

        val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), app.packageName)
        AlarmReceiver().onReceive(app, AlarmIntents.toIntent(app, alarm, keys))
        assertTrue(fired.await(5, TimeUnit.SECONDS), "the handler was never called")

        runBlocking { scheduler.cancel("a") }
        assertEquals(null, shadowOf(manager).nextScheduledAlarm)
    }
}
