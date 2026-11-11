package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The receiver is not exported and registers for no system broadcast, yet a rebooted, time-shifted or
 * updated device is exactly when an app author wires one to it by mistake. Whatever intent reaches
 * `onReceive` that is not one of this module's alarms is dropped silently (KDoc of [AlarmReceiver]),
 * and one that carries an id and a type is never dropped for a damaged body: dropping would lose a
 * reminder the user asked for. Neither may crash the process, which is what an exception on the
 * main thread in `onReceive` would do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverSystemBroadcastAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    private class Collecting(override val type: String, expected: Int) : AlarmHandler {
        val latch = CountDownLatch(expected)
        val fired: CopyOnWriteArrayList<ScheduledAlarm> = CopyOnWriteArrayList()

        override suspend fun onFire(alarm: ScheduledAlarm) {
            fired += alarm
            latch.countDown()
        }
    }

    @After
    fun clearRegistration() {
        AlarmDispatch.reset()
    }

    private fun valid(id: String): Intent = AlarmIntents.toIntent(
        context,
        ScheduledAlarm(id, "T", 5_000L, AlarmNotification("title", "body", "chan"), mapOf("k" to "v")),
        keys,
    )

    private fun deliver(vararg intents: Intent) {
        intents.forEach { AlarmReceiver().onReceive(context, it) }
    }

    @Test
    fun `system broadcasts that carry no alarm are dropped and the next real alarm still arrives`() {
        val handler = Collecting("T", expected = 1)
        AlarmDispatch.install(keys, listOf(handler))
        val actions: List<String?> = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            null,
        )
        actions.forEach { action: String? ->
            deliver(Intent(context, AlarmReceiver::class.java).apply { this.action = action })
        }
        deliver(valid("real"))

        assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "the real alarm never arrived")
        Thread.sleep(SETTLE_MS)
        assertEquals(listOf("real"), handler.fired.map { it.id })
    }

    @Test
    fun `an intent whose id or type is of the wrong type, blank or missing is dropped`() {
        val handler = Collecting("T", expected = 1)
        AlarmDispatch.install(keys, listOf(handler))
        deliver(
            valid("a").apply { putExtra(keys.idKey, 5) },
            valid("b").apply { putExtra(keys.typeKey, true) },
            valid("c").apply { putExtra(keys.idKey, "   ") },
            valid("d").apply { putExtra(keys.typeKey, "") },
            valid("e").apply { removeExtra(keys.idKey) },
            valid("f").apply { removeExtra(keys.typeKey) },
            valid("g").apply { putExtra(keys.idKey, arrayOf("g")) },
            valid("real"),
        )
        assertTrue(handler.latch.await(10, TimeUnit.SECONDS))
        Thread.sleep(SETTLE_MS)
        assertEquals(listOf("real"), handler.fired.map { it.id })
    }

    @Test
    fun `an alarm with an id and a type but a damaged body is delivered with defaults and never crashes`() {
        val handler = Collecting("T", expected = 1)
        AlarmDispatch.install(keys, listOf(handler))
        deliver(
            valid("damaged").apply {
                putExtra(AlarmExtraNames.TITLE, 5)
                putExtra(AlarmExtraNames.BODY, true)
                putExtra(AlarmExtraNames.CHANNEL, 7L)
                putExtra(AlarmExtraNames.FIRE_AT, "oops")
                putExtra(AlarmExtraNames.PAYLOAD, "not a bundle")
            },
        )
        assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "an alarm that has an id and a type must not be dropped")
        val alarm: ScheduledAlarm = handler.fired.single()
        assertEquals("damaged", alarm.id)
        assertEquals("", alarm.notification.title)
        assertEquals("", alarm.notification.body)
        assertEquals("", alarm.notification.channelId)
        assertEquals(0L, alarm.fireAtEpochMillis)
        assertEquals(emptyMap(), alarm.payload)
    }

    @Test
    fun `a payload bundle with non-string entries contributes only its strings`() {
        val handler = Collecting("T", expected = 1)
        AlarmDispatch.install(keys, listOf(handler))
        val payload = Bundle().apply {
            putString("a", "1")
            putInt("n", 2)
            putBundle("nested", Bundle().apply { putString("x", "y") })
            putStringArray("arr", arrayOf("x"))
            putString("empty", "")
        }
        deliver(valid("mixed").apply { putExtra(AlarmExtraNames.PAYLOAD, payload) })
        assertTrue(handler.latch.await(10, TimeUnit.SECONDS))
        assertEquals(mapOf("a" to "1", "empty" to ""), handler.fired.single().payload)
    }

    private companion object {
        const val SETTLE_MS: Long = 300L
    }
}
