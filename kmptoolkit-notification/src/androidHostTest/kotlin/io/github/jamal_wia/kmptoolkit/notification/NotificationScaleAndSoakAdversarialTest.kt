package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 12 (scale and soak): thousands of notifications, ids and channels through the real Android
 * notifier, and a seeded soak on a fake clock that jumps by days. The soak's default is bounded (a few
 * thousand steps); set KMPTOOLKIT_SOAK_STEPS (environment) or kmptoolkit.soak.steps (system property) for
 * the long form. After the run the five maps of the coalescer are measured by reflection: nothing may be
 * left behind by ids that were abandoned, cancelled or overtaken.
 */
@RunWith(AndroidJUnit4::class)
class NotificationScaleAndSoakAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val shown: List<Notification> get() = shadowOf(manager).allNotifications
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    private fun titles(): List<String> = shown.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }

    private fun mapSize(coalescer: ProgressCoalescer, field: String): Int {
        val f = ProgressCoalescer::class.java.getDeclaredField(field)
        f.isAccessible = true
        return (f.get(coalescer) as Map<*, *>).size
    }

    private fun mapSizes(coalescer: ProgressCoalescer): Map<String, Int> =
        listOf("posted", "latest", "lastShown", "inFlight", "claims").associateWith { mapSize(coalescer, it) }

    private fun notifier(coalescer: ProgressCoalescer): AndroidNotifier =
        AndroidNotifier(context, permissions, NotificationRenderer(context, "test.ACTION"), coalescer)

    @Test
    fun `twenty five hundred ids on three hundred channels are all showing then cancel one by one and cancelAll leave nothing`() {
        runBlocking {
            withTimeout(150_000) {
                val coalescer = ProgressCoalescer(minInterval = 0.milliseconds)
                val notifier: AndroidNotifier = notifier(coalescer)
                val channels: List<NotificationChannelSpec> = (0 until CHANNELS).map {
                    NotificationChannelSpec("channel-$it", "Channel $it", importance = NotificationImportance.entries[it % 3])
                }
                val ids: List<String> = (0 until IDS).map { "n-$it" }
                ids.forEachIndexed { index, id ->
                    val result = notifier.post(id, LocalNotification(id, "body", channels[index % CHANNELS]))
                    assertEquals(NotificationResult.Posted, result, "post $id")
                }
                assertEquals(IDS, shown.size)
                assertEquals(ids.toSet(), titles().toSet(), "two ids collapsed into one notification")
                assertEquals(CHANNELS, manager.notificationChannels.count { it.id.startsWith("channel-") })
                assertTrue(notifier.sweptLegacyCountForTest() <= 256, "the sweep cache is documented as bounded by 256")

                // Re-posting every id replaces, never adds.
                ids.forEachIndexed { index, id ->
                    notifier.post(id, LocalNotification("$id-v2", "body", channels[(index + 1) % CHANNELS]))
                }
                assertEquals(IDS, shown.size, "a re-post of a showing id added a notification")
                assertEquals(ids.map { "$it-v2" }.toSet(), titles().toSet())

                ids.filterIndexed { index, _ -> index % 2 == 0 }.forEach { notifier.cancel(it) }
                assertEquals(IDS / 2, shown.size)
                assertEquals(ids.filterIndexed { index, _ -> index % 2 == 1 }.map { "$it-v2" }.toSet(), titles().toSet())
                assertTrue(notifier.sweptLegacyCountForTest() <= 256)

                notifier.cancelAll()
                assertEquals(0, shown.size)
                assertEquals(0, notifier.sweptLegacyCountForTest())
                assertEquals(mapOf("posted" to 0, "latest" to 0, "lastShown" to 0, "inFlight" to 0, "claims" to 0), mapSizes(coalescer))
                // Channels outlive cancelAll, as the guide says: a user's settings are not ours to delete.
                assertEquals(CHANNELS, manager.notificationChannels.count { it.id.startsWith("channel-") })
            }
        }
    }

    @Test
    fun `the 2x sweep cache never holds more than 256 ids however many are posted and cancel empties its entry`() {
        runBlocking {
            val notifier: AndroidNotifier = notifier(ProgressCoalescer(minInterval = 0.milliseconds))
            val channel = NotificationChannelSpec("c", "C")
            repeat(1_500) { n ->
                notifier.post("sweep-$n", LocalNotification("t", "b", channel))
                assertTrue(notifier.sweptLegacyCountForTest() <= 256, "after $n posts")
            }
            notifier.cancelAll()
            assertEquals(0, notifier.sweptLegacyCountForTest())
        }
    }

    @Test
    fun `a seeded soak with a clock that jumps by days keeps the tray equal to the model and leaves no state in the coalescer`() {
        val steps: Int = System.getProperty("kmptoolkit.soak.steps")?.toIntOrNull()
            ?: System.getenv("KMPTOOLKIT_SOAK_STEPS")?.toIntOrNull()
            ?: DEFAULT_STEPS
        println("soak steps=$steps")
        for (seed in 0 until SEEDS) {
            val random = Random(seed)
            val time = TestTimeSource()
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = time)
            val notifier: AndroidNotifier = notifier(coalescer)
            val channel = NotificationChannelSpec("soak", "Soak", importance = NotificationImportance.Low)
            val model: MutableSet<String> = mutableSetOf()
            var fresh = 0
            val log = StringBuilder()

            fun frame(id: String, percent: Int?): LocalNotification = LocalNotification(
                title = id, body = "b", channel = channel,
                progress = percent?.let { NotificationProgress.Determinate(it) },
            )

            runBlocking {
                repeat(steps) { step ->
                    time += when (random.nextInt(100)) {
                        in 0 until 70 -> random.nextInt(0, 300).milliseconds
                        in 70 until 95 -> random.nextInt(1, 120).seconds
                        else -> random.nextInt(1, 4).days
                    }
                    val id: String = if (random.nextInt(10) == 0) "fresh-${fresh++}" else "id-${random.nextInt(40)}"
                    when (random.nextInt(100)) {
                        in 0 until 60 -> {
                            val result = notifier.post(id, frame(id, random.nextInt(0, 101)))
                            log.append("P($id,${result::class.simpleName}) ")
                            when (result) {
                                NotificationResult.Posted -> model += id
                                NotificationResult.Coalesced -> Unit
                                else -> error("seed $seed step $step: $result for a healthy post. $log")
                            }
                        }
                        in 60 until 75 -> {
                            val result = notifier.post(id, frame(id, null))
                            assertEquals(NotificationResult.Posted, result, "seed $seed step $step: a terminal frame must always post")
                            model += id
                        }
                        in 75 until 95 -> {
                            notifier.cancel(id)
                            model -= id
                        }
                        else -> {
                            notifier.cancelAll()
                            model.clear()
                        }
                    }
                    if (step % 250 == 0) {
                        assertEquals(model.sorted(), titles().sorted(), "seed $seed step $step: tray differs from the model. $log")
                        assertTrue(notifier.sweptLegacyCountForTest() <= 256)
                    }
                }
                assertEquals(model.sorted(), titles().sorted(), "seed $seed: final tray differs from the model")

                // Two days of silence, then one frame: it posts like the first frame of a run and its arrival
                // prunes everything the run left behind (ids abandoned mid-run, overtaken, refused).
                time += 2.days
                assertEquals(NotificationResult.Posted, notifier.post("final", frame("final", 50)))
                val sizes: Map<String, Int> = mapSizes(coalescer)
                assertTrue((sizes["posted"] ?: 0) <= 1 && (sizes["latest"] ?: 0) <= 1 && (sizes["lastShown"] ?: 0) <= 1, "seed $seed: stale state survived two quiet days: $sizes")
                assertEquals(0, sizes["inFlight"], "seed $seed: $sizes")
                assertEquals(0, sizes["claims"], "seed $seed: a claim was never released: $sizes")
                assertEquals(0, coalescer.claimCountForTest())

                notifier.cancelAll()
                assertEquals(0, shown.size)
                assertEquals(mapOf("posted" to 0, "latest" to 0, "lastShown" to 0, "inFlight" to 0, "claims" to 0), mapSizes(coalescer), "seed $seed")
                // A quiet 11 minutes after the final cancel changes nothing.
                time += 11.minutes
                assertEquals(NotificationResult.Posted, notifier.post("again", frame("again", 10)))
                assertEquals(listOf("again"), titles())
                notifier.cancelAll()
            }
        }
    }

    @Test
    fun `three thousand concurrent posts on distinct ids all land and on one id leave exactly one notification and no claim`() {
        runBlocking {
            withTimeout(120_000) {
                val coalescer = ProgressCoalescer(minInterval = 0.milliseconds)
                val notifier: AndroidNotifier = notifier(coalescer)
                val channel = NotificationChannelSpec("flood", "Flood")
                val distinct: List<NotificationResult> = (0 until 3_000).map { n ->
                    async(Dispatchers.Default) {
                        notifier.post("c-$n", LocalNotification("c-$n", "b", channel, progress = NotificationProgress.Determinate(n % 101)))
                    }
                }.awaitAll()
                assertEquals(3_000, distinct.count { it == NotificationResult.Posted }, "results: ${distinct.groupBy { it::class.simpleName }.mapValues { it.value.size }}")
                assertEquals(3_000, shown.size)
                assertEquals(0, coalescer.claimCountForTest())

                notifier.cancelAll()
                val same: List<NotificationResult> = (0 until 3_000).map { n ->
                    async(Dispatchers.Default) { notifier.post("one", LocalNotification("v$n", "b", channel)) }
                }.awaitAll()
                assertTrue(same.all { it == NotificationResult.Posted || it == NotificationResult.Coalesced }, "$same")
                assertTrue(same.any { it == NotificationResult.Posted })
                assertEquals(1, shown.size, "one id is one notification however many posts race for it")
                assertEquals(0, coalescer.claimCountForTest())
                notifier.cancelAll()
                assertEquals(0, mapSize(coalescer, "inFlight"))
            }
        }
    }

    private companion object {
        const val IDS: Int = 2_500
        const val CHANNELS: Int = 300
        const val DEFAULT_STEPS: Int = 4_000
        const val SEEDS: Int = 2
    }
}
