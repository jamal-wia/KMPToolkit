package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.random.Random
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

/** Random ids from an alphabet of awkward but valid code points (no unpaired surrogates): each is its own alarm and cancels alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerRandomIdsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private val alphabet: List<String> = listOf(
        "a", "A", "0", "_", "-", ".", "~", "!", "'", "(", ")", "*", "%", "/", "\\", ":", "?", "#", "&", "=", "+", " ", "\t", "\n",
        "\u0000", "\u001F", "\u007F", "\u0080", " ", "­", "​", "‍", "‮", "﻿", "�", "￾", "￿", "",
        "́", "é", "İ", "ß", "ﬁ", "Å", "Å", "😀", "🇩🇪", "𝄞", "中", "ا",
    )

    @Test
    fun `random awkward ids are distinct alarms that cancel alone`() {
        val random = Random(20261108)
        val ids: List<String> = generateSequence { (1..random.nextInt(1, 6)).joinToString("") { alphabet[random.nextInt(alphabet.size)] } }
            .filter { it.isNotBlank() }.distinct().take(1_500).toList()
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        ids.forEachIndexed { index: Int, id: String ->
            runBlocking {
                scheduler.schedule(
                    ScheduledAlarm(
                        id = id, type = "T", fireAtEpochMillis = index.toLong(),
                        notification = AlarmNotification(title = "t$index", body = "b", channelId = "c"),
                    ),
                )
            }
        }
        assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(ids.toSet(), shadowAlarmManager.scheduledAlarms.map { requireNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).id }.toSet())
        // Cancel every other id: exactly those disappear.
        ids.filterIndexed { i, _ -> i % 2 == 0 }.forEach { runBlocking { scheduler.cancel(it) } }
        val left: Set<String> = shadowAlarmManager.scheduledAlarms.map { requireNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).id }.toSet()
        assertEquals(ids.filterIndexed { i, _ -> i % 2 == 1 }.toSet(), left)
    }
}
