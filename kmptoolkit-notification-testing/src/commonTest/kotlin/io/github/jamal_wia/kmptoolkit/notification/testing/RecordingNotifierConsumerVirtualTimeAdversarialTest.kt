package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import io.github.jamal_wia.kmptoolkit.notification.NotificationOptions
import io.github.jamal_wia.kmptoolkit.notification.NotificationProgress
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import io.github.jamal_wia.kmptoolkit.notification.Notifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * Round 20 (consumer setups): how a consumer's own tests use [RecordingNotifier] under `runTest`
 * with virtual time, a cancelled collaborator and a decorator, which is what the testing guide
 * tells them to do. The double must not read a clock, must not hold the consumer's coroutine
 * after a cancel, and must leave nothing half recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingNotifierConsumerVirtualTimeAdversarialTest {

    private val channel = NotificationChannelSpec("dl", "Downloads")

    private fun frame(percent: Int): LocalNotification =
        LocalNotification("Downloading", "$percent%", channel, progress = NotificationProgress.Determinate(percent), ongoing = true)

    private class Presenter(private val notifier: Notifier, private val channel: NotificationChannelSpec) {
        suspend fun run(steps: Int) {
            try {
                for (percent in 1..steps) {
                    delay(1.seconds)
                    notifier.post("download", LocalNotification("Downloading", "$percent%", channel, progress = NotificationProgress.Determinate(percent)))
                }
                notifier.post("download", LocalNotification("Done", "file", channel))
            } catch (cancelled: CancellationException) {
                notifier.cancel("download")
                throw cancelled
            }
        }
    }

    @Test
    fun `a presenter that delays between frames runs in virtual time and the double answers every frame`() = runTest {
        val notifier = RecordingNotifier()

        Presenter(notifier, channel).run(100)

        assertEquals(101, notifier.posted.size)
        assertEquals(1, notifier.showing.size)
        assertEquals("Done", notifier.showing.getValue("download").title)
        assertEquals(100_000L, currentTime, "exactly the presenter's own delays elapsed, the double added none")
    }

    @Test
    fun `a presenter cancelled halfway leaves its frames recorded and the cancel in the cancelled list`() = runTest {
        val notifier = RecordingNotifier()
        val job: Job = launch(StandardTestDispatcher(testScheduler)) { Presenter(notifier, channel).run(100) }

        advanceTimeBy(50_500)
        job.cancel()
        job.join()

        assertEquals(50, notifier.posted.size, "one frame per elapsed second, none after the cancel")
        assertEquals(listOf("download"), notifier.cancelled)
        assertTrue(notifier.showing.isEmpty(), "the consumer's cancel removed what was showing")
    }

    @Test
    fun `two coroutines interleaving on one test scheduler record in the order they ran`() = runTest {
        val notifier = RecordingNotifier()
        val first: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(3) { index: Int -> notifier.post("a", frame(index)); yield() }
        }
        val second: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(3) { index: Int -> notifier.post("b", frame(index)); yield() }
        }
        first.join()
        second.join()

        assertEquals(listOf("a", "b", "a", "b", "a", "b"), notifier.posted.map { it.id })
        assertEquals(setOf("a", "b"), notifier.showing.keys)
    }

    @Test
    fun `a decorator that forgets the options overload drops them and the recording says so`() = runTest {
        val inner = RecordingNotifier()
        val twoArgumentOnly: Notifier = object : Notifier {
            override suspend fun post(id: String, notification: LocalNotification): NotificationResult = inner.post(id, notification)
            override fun cancel(id: String) = inner.cancel(id)
            override fun cancelAll() = inner.cancelAll()
        }
        val options = NotificationOptions(alertOnce = false, mediaStyle = true)

        twoArgumentOnly.post("x", frame(5), options)

        assertEquals(NotificationOptions.DEFAULT, inner.posted.single().options, "documented: the interface default ignores the options")
    }

    @Test
    fun `a decorator that overrides the options overload passes them through to the recording`() = runTest {
        val inner = RecordingNotifier()
        val complete: Notifier = object : Notifier {
            override suspend fun post(id: String, notification: LocalNotification): NotificationResult = inner.post(id, notification)
            override suspend fun post(id: String, notification: LocalNotification, options: NotificationOptions): NotificationResult =
                inner.post(id, notification, options)
            override fun cancel(id: String) = inner.cancel(id)
            override fun cancelAll() = inner.cancelAll()
        }
        val options = NotificationOptions(alertOnce = false, mediaStyle = true)

        complete.post("x", frame(5), options)
        complete.post("y", frame(6))

        assertEquals(options, inner.posted[0].options)
        assertEquals(NotificationOptions.DEFAULT, inner.posted[1].options)
    }

    @Test
    fun `a result switched mid test changes the next answer only and a refused frame shows nothing new`() = runTest {
        val notifier = RecordingNotifier()
        assertEquals(NotificationResult.Posted, notifier.post("x", frame(1)))

        notifier.result = NotificationResult.NotificationsDisabled
        assertEquals(NotificationResult.NotificationsDisabled, notifier.post("x", frame(2)))

        assertEquals("1%", notifier.showing.getValue("x").body)
        assertEquals(2, notifier.posted.size)
        notifier.clear()
        assertTrue(notifier.posted.isEmpty() && notifier.showing.isEmpty() && notifier.cancelled.isEmpty())
    }
}
