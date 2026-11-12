package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import io.github.jamal_wia.kmptoolkit.notification.NotificationProgress
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * Round 12 (scale and documentation as a test) for RecordingNotifier: a seeded sequence of fifty thousand
 * operations against a reference model, with the documented laws checked at the end, plus the two samples
 * of docs/kmptoolkit-notification/06-testing.md run against a small presenter.
 */
class RecordingNotifierScaleAdversarialTest {

    private val channel = NotificationChannelSpec("c", "C")

    @Test
    fun fiftyThousandSeededOperationsAgreeWithAReferenceModel() = runTest {
        val random = Random(12)
        val notifier = RecordingNotifier()
        val showing: MutableMap<String, String> = linkedMapOf()
        var posts = 0
        var cancels = 0
        var cancelAlls = 0
        repeat(50_000) { step ->
            val id = "id-${random.nextInt(500)}"
            when (random.nextInt(100)) {
                in 0 until 70 -> {
                    notifier.result = when (random.nextInt(10)) {
                        0 -> NotificationResult.Coalesced
                        1 -> NotificationResult.PermissionDenied
                        else -> NotificationResult.Posted
                    }
                    val result = notifier.post(id, LocalNotification("t$step", "b", channel))
                    assertEquals(notifier.result, result)
                    posts++
                    if (result == NotificationResult.Posted) showing[id] = "t$step"
                }
                in 70 until 98 -> {
                    notifier.cancel(id)
                    cancels++
                    showing.remove(id)
                }
                else -> {
                    notifier.cancelAll()
                    cancelAlls++
                    showing.clear()
                }
            }
        }
        assertEquals(posts, notifier.posted.size)
        assertEquals(cancels, notifier.cancelled.size)
        assertEquals(cancelAlls, notifier.cancelAllCount)
        assertEquals(showing, notifier.showing.mapValues { it.value.title })
        notifier.clear()
        assertEquals(0, notifier.posted.size + notifier.showing.size + notifier.cancelled.size + notifier.cancelAllCount)
    }

    private class DownloadPresenter(private val notifier: RecordingNotifier) {
        suspend fun onProgress(percent: Int) {
            notifier.post("download", LocalNotification("Downloading", "$percent", NotificationChannelSpec("d", "D"), progress = NotificationProgress.Determinate(percent)))
        }

        suspend fun onFinished() {
            notifier.post("download", LocalNotification("Download complete", "file", NotificationChannelSpec("d", "D")))
        }

        suspend fun run(): String {
            onProgress(10)
            onFinished()
            return "Success"
        }
    }

    @Test
    fun theTwoSamplesOfTheTestingGuideRunAsWritten() = runTest {
        val notifier = RecordingNotifier()
        val presenter = DownloadPresenter(notifier)
        presenter.onProgress(50)
        presenter.onFinished()
        assertEquals(1, notifier.showing.size)
        assertEquals("Download complete", notifier.showing.getValue("download").title)
        assertEquals(2, notifier.posted.size)

        val denied = RecordingNotifier(result = NotificationResult.PermissionDenied)
        assertEquals("Success", DownloadPresenter(denied).run())
        assertEquals(0, denied.showing.size, "a refused post never reaches the screen")
        assertEquals(2, denied.posted.size, "but every attempt is recorded")
        assertSame(io.github.jamal_wia.kmptoolkit.notification.NotificationOptions.DEFAULT, denied.posted.last().options)
    }
}
