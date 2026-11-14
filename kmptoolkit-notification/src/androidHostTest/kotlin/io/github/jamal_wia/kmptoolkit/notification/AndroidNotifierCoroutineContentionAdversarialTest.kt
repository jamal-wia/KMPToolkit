package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * High contention on one notifier from coroutines, not threads: many coroutines on a multi-threaded dispatcher post
 * frames of a handful of ids while others cancel the posting jobs at random moments (a hard cancel, a short timeout,
 * a cancel while the build yields), and a few more call cancel and cancelAll. Invariants once the storm has ended:
 * no job is stuck (a hard time limit), only cancellation escapes a cancelled post, the coalescer holds no claim and
 * no frame in flight, every remembered baseline is a frame the tray shows, the tray holds at most one frame per id,
 * and every id accepts a fresh frame again.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierCoroutineContentionAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "contention", name = "Contention")

    private class YieldingRenderer(context: Context, private val seed: Long) : NotificationRenderer(context, "test.ACTION") {
        private val calls = AtomicInteger()

        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val random = Random(seed * 17 + calls.incrementAndGet())
            if (random.nextInt(4) == 0) Thread.yield()
            val built: Notification = super.build(id, notification, options, iconResId)
            if (random.nextInt(4) == 0) Thread.yield()
            if (random.nextInt(10) == 0) throw IllegalStateException("refused")
            return built
        }
    }

    private fun field(coalescer: ProgressCoalescer, name: String): Map<*, *> =
        ProgressCoalescer::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(coalescer) as Map<*, *>

    @Test
    fun `cancelling posting coroutines at random moments leaves no claim no frame in flight and a tray the baseline agrees with`() {
        val ids: List<String> = listOf("a", "b", "c")
        repeat(ROUNDS) { round: Int ->
            runBlocking { shadowOf(manager).allNotifications.let { manager.cancelAll() } }
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
            val notifier: Notifier = AndroidNotifier(context, permissions, YieldingRenderer(context, round.toLong()), coalescer, emptySet())
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            runBlocking {
                withTimeout(JOIN_MILLIS) {
                    val scope = CoroutineScope(Dispatchers.Default)
                    val jobs: List<Job> = List(COROUTINES) { index: Int ->
                        scope.launch {
                            val random = Random(round * 1000 + index)
                            val posts: MutableList<Job> = mutableListOf()
                            repeat(OPS) {
                                val id: String = ids[random.nextInt(ids.size)]
                                when (val dice: Int = random.nextInt(100)) {
                                    in 0..4 -> notifier.cancel(id)
                                    in 5..6 -> notifier.cancelAll()
                                    in 7..30 -> {
                                        val job: Job = launch {
                                            try {
                                                notifier.post(id, frame(id, NotificationProgress.Determinate(random.nextInt(0, 100))))
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Throwable) {
                                                escaped += "${e.javaClass.simpleName}: ${e.message}"
                                            }
                                        }
                                        posts += job
                                        if (random.nextBoolean()) job.cancel()
                                    }
                                    in 31..40 -> {
                                        try {
                                            withTimeoutOrNull(random.nextLong(1, 3)) {
                                                notifier.post(id, frame(id, NotificationProgress.Determinate(dice)))
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Throwable) {
                                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                                        }
                                    }
                                    else -> if (posts.isNotEmpty()) posts[random.nextInt(posts.size)].cancel()
                                }
                                if (random.nextInt(8) == 0) delay(1)
                            }
                            posts.joinAll()
                        }
                    }
                    jobs.joinAll()
                }
            }
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped a post")
            assertEquals(0, coalescer.claimCountForTest(), "round $round: a claim is left with no caller")
            assertEquals(0, field(coalescer, "inFlight").values.sumOf { (it as List<*>).size }, "round $round: frames in flight with no caller")
            @Suppress("UNCHECKED_CAST")
            val posted: Map<String, ProgressCoalescer.Post> = (field(coalescer, "posted") as Map<String, ProgressCoalescer.Post>).toMap()
            posted.forEach { (id: String, record: ProgressCoalescer.Post) ->
                val onTray: Notification? = shadowOf(manager).getNotification(id, 1)
                val trayProgress: Int? = onTray?.extras?.takeIf { it.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }?.getInt(Notification.EXTRA_PROGRESS)
                assertTrue(
                    onTray != null && trayProgress != null && (trayProgress / 10) * 10 == record.bucket,
                    "round $round: '$id' is remembered at bucket ${record.bucket} but the tray holds $trayProgress (present=${onTray != null})",
                )
            }
            assertTrue(shadowOf(manager).allNotifications.size <= ids.size, "round $round: more tray frames than ids")
            ids.forEach { id: String ->
                notifier.cancel(id)
                val result: NotificationResult = runBlocking { notifier.post(id, frame(id, NotificationProgress.Determinate(40))) }
                assertTrue(result == NotificationResult.Posted || result is NotificationResult.Failed, "round $round: fresh frame of '$id' was $result")
            }
            notifier.cancelAll()
            assertEquals(0, shadowOf(manager).allNotifications.size, "round $round: cancelAll left frames")
        }
    }

    private fun frame(id: String, progress: NotificationProgress?): LocalNotification =
        LocalNotification(title = "t-$id", body = "b", channel = channel, progress = progress)

    private companion object {
        const val ROUNDS: Int = 6
        const val COROUTINES: Int = 24
        const val OPS: Int = 120
        const val JOIN_MILLIS: Long = 60_000L
    }
}
