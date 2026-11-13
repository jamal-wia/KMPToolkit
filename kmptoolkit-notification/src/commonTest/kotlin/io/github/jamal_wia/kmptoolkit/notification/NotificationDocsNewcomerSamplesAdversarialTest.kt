package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * Round 24 (docs-only newcomer): the Kotlin samples of docs/kmptoolkit-notification/01 to 04 and 06, typed in
 * as a reader would copy them (the app's own `strings`, `log` and screens replaced by plain values) and run
 * against `RecordingNotifier`, in the order of the getting-started steps. Nothing here was written from source.
 * Runs on the Android host and on iOS.
 */
class NotificationDocsNewcomerSamplesAdversarialTest {

    private class Strings {
        val downloadsChannelName: String = "Downloads"
        val downloadsChannelDescription: String = "Download progress"
        val downloading: String = "Downloading"
        val downloadComplete: String = "Download complete"
        val fileName: String = "file.zip"
        val remindersChannelName: String = "Reminders"
        val remindersChannelHint: String = "Study reminders"
        val timeToStudy: String = "Time to study"
        val lessonName: String = "Lesson 42"
        fun percent(percent: Int): String = "$percent%"
    }

    private val strings = Strings()

    // ----- 01-overview sample, verbatim shape -----

    private class DownloadPresenter(private val notifier: Notifier, private val strings: Strings) {
        val reactions: MutableList<String> = mutableListOf()

        private val channel = NotificationChannelSpec(
            id = "downloads",
            name = strings.downloadsChannelName,
            description = strings.downloadsChannelDescription,
            importance = NotificationImportance.Low,
        )

        suspend fun onProgress(percent: Int) {
            notifier.post(
                id = "download",
                notification = LocalNotification(
                    title = strings.downloading,
                    body = "$percent%",
                    channel = channel,
                    progress = NotificationProgress.Determinate(percent),
                    ongoing = true,
                ),
            )
        }

        private fun completedNotification(): LocalNotification =
            LocalNotification(title = strings.downloadComplete, body = strings.fileName, channel = channel)

        suspend fun onFinished(): Unit = when (val result = notifier.post("download", completedNotification())) {
            NotificationResult.Posted, NotificationResult.Coalesced -> Unit
            NotificationResult.PermissionDenied -> reactions += "askForPermissionLater"
            NotificationResult.NotificationsDisabled -> reactions += "offerSettingsLink"
            is NotificationResult.ChannelBlocked -> reactions += "offerSettingsLink:${result.channelId}"
            is NotificationResult.Failed -> reactions += "log.error:${result.cause?.message}"
        }
    }

    @Test
    fun `overview sample - progress then finish leaves one completed notification and every result has its reaction`() = runTest {
        val notifier = RecordingNotifier()
        val presenter = DownloadPresenter(notifier, strings)
        presenter.onProgress(50)
        assertEquals(NotificationProgress.Determinate(50), notifier.showing.getValue("download").progress)
        assertTrue(notifier.showing.getValue("download").ongoing)
        presenter.onFinished()
        assertEquals(1, notifier.showing.size)
        assertEquals(strings.downloadComplete, notifier.showing.getValue("download").title)
        assertNull(notifier.showing.getValue("download").progress)
        assertEquals(2, notifier.posted.size)
        assertEquals(emptyList(), presenter.reactions)

        val reactions: List<Pair<NotificationResult, String?>> = listOf(
            NotificationResult.Coalesced to null,
            NotificationResult.PermissionDenied to "askForPermissionLater",
            NotificationResult.NotificationsDisabled to "offerSettingsLink",
            NotificationResult.ChannelBlocked("downloads") to "offerSettingsLink:downloads",
            NotificationResult.Failed(IllegalStateException("boom")) to "log.error:boom",
        )
        for ((result: NotificationResult, expected: String?) in reactions) {
            val refusing = DownloadPresenter(RecordingNotifier(result = result), strings)
            refusing.onFinished()
            assertEquals(listOfNotNull(expected), refusing.reactions, "reaction to $result")
        }
    }

    // ----- 02-getting-started, steps 5 and 6 in order -----

    @Test
    fun `getting started steps 5 and 6 - post a reminder handle every result replace by id cancel by id`() = runTest {
        val notifier: Notifier = RecordingNotifier()
        val recorder: RecordingNotifier = notifier as RecordingNotifier
        val remindersChannel = NotificationChannelSpec(
            id = "reminders",
            name = strings.remindersChannelName,
            description = strings.remindersChannelHint,
            importance = NotificationImportance.Default,
        )
        val result: NotificationResult = notifier.post(
            id = "reminder-42",
            notification = LocalNotification(
                title = strings.timeToStudy,
                body = strings.lessonName,
                channel = remindersChannel,
                contentExtras = mapOf("lessonId" to "42"),
            ),
        )
        assertEquals(NotificationResult.Posted, result)
        assertEquals(mapOf("lessonId" to "42"), recorder.showing.getValue("reminder-42").contentExtras)

        // "re-posting it replaces the notification in place"
        notifier.post("reminder-42", LocalNotification("Again", "again", remindersChannel))
        assertEquals(listOf("reminder-42"), recorder.showing.keys.toList())
        assertEquals("Again", recorder.showing.getValue("reminder-42").title)

        // "cancel("reminder-42") takes it down"
        notifier.cancel("reminder-42")
        assertTrue(recorder.showing.isEmpty())
        assertEquals(listOf("reminder-42"), recorder.cancelled)

        val handled: MutableList<String> = mutableListOf()
        val all: List<NotificationResult> = listOf(
            NotificationResult.Posted, NotificationResult.Coalesced, NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled, NotificationResult.ChannelBlocked("c"),
            NotificationResult.Failed(RuntimeException("x")),
        )
        for (candidate: NotificationResult in all) {
            when (candidate) {
                NotificationResult.Posted, NotificationResult.Coalesced -> handled += "unit"
                NotificationResult.PermissionDenied -> handled += "promptForNotifications"
                NotificationResult.NotificationsDisabled -> handled += "showSettingsHint"
                is NotificationResult.ChannelBlocked -> handled += "showSettingsHint"
                is NotificationResult.Failed -> handled += "logger.error ${candidate.cause?.message}"
            }
        }
        assertEquals(
            listOf("unit", "unit", "promptForNotifications", "showSettingsHint", "showSettingsHint", "logger.error x"),
            handled,
        )
    }

    // ----- 03-guide: progress run, options, decorators -----

    @Test
    fun `guide progress sample - the terminal frame under the same id replaces the progress notification`() = runTest {
        val notifier = RecordingNotifier()
        val downloadsChannel = NotificationChannelSpec("downloads", "Downloads", importance = NotificationImportance.Low)

        suspend fun onBytes(downloaded: Long, total: Long) {
            val percent: Int = (downloaded * 100 / total).toInt()
            notifier.post(
                id = "download",
                notification = LocalNotification(
                    title = strings.downloading,
                    body = strings.percent(percent),
                    channel = downloadsChannel,
                    progress = NotificationProgress.Determinate(percent),
                    ongoing = true,
                ),
            )
        }

        suspend fun onFinished() {
            notifier.post(
                id = "download",
                notification = LocalNotification(
                    title = strings.downloadComplete,
                    body = strings.fileName,
                    channel = downloadsChannel,
                    progress = null,
                    ongoing = false,
                    contentExtras = mapOf("screen" to "downloads"),
                ),
            )
        }

        onBytes(10, 100)
        onBytes(55, 100)
        assertEquals(NotificationProgress.Determinate(55), notifier.showing.getValue("download").progress)
        onFinished()
        val shown: LocalNotification = notifier.showing.getValue("download")
        assertEquals(1, notifier.showing.size)
        assertNull(shown.progress)
        assertFalse(shown.ongoing)
        assertEquals(mapOf("screen" to "downloads"), shown.contentExtras)
        assertEquals(3, notifier.posted.size)
    }

    @Test
    fun `guide options sample - alertOnce false on the three argument form DEFAULT on the two argument form`() = runTest {
        val notifier = RecordingNotifier()
        val reminder = LocalNotification("Lesson", "Soon", NotificationChannelSpec("reminders", "Reminders"))
        notifier.post("lesson-reminder", reminder, NotificationOptions(alertOnce = false))
        assertFalse(notifier.posted.last().options.alertOnce)
        notifier.post("lesson-reminder", reminder)
        assertTrue(notifier.posted.last().options.alertOnce)
        assertEquals(NotificationOptions.DEFAULT, notifier.posted.last().options)

        val stop = NotificationAction(id = "stop-adhan", label = "Stop")
        val options = NotificationOptions(
            dismissAction = stop,
            mediaStyle = true,
            actionIcons = mapOf(stop.id to NotificationIcon.AndroidDrawable(7)),
        )
        notifier.post("adhan", LocalNotification("Adhan", "now", reminder.channel, actions = listOf(stop)), options)
        assertEquals(stop, notifier.posted.last().options.dismissAction)
        assertTrue(notifier.posted.last().options.mediaStyle)
        assertEquals(NotificationIcon.AndroidDrawable(7), notifier.posted.last().options.actionIcons.getValue("stop-adhan"))
    }

    @Test
    fun `guide common mistake - a decorator forwarding only the two argument post drops the options and one forwarding both keeps them`() = runTest {
        class TwoArgumentOnly(private val inner: Notifier) : Notifier {
            override suspend fun post(id: String, notification: LocalNotification): NotificationResult = inner.post(id, notification)
            override fun cancel(id: String) = inner.cancel(id)
            override fun cancelAll() = inner.cancelAll()
        }

        class ForwardsBoth(private val inner: Notifier) : Notifier {
            override suspend fun post(id: String, notification: LocalNotification): NotificationResult = inner.post(id, notification)
            override suspend fun post(id: String, notification: LocalNotification, options: NotificationOptions): NotificationResult =
                inner.post(id, notification, options)

            override fun cancel(id: String) = inner.cancel(id)
            override fun cancelAll() = inner.cancelAll()
        }

        val frame = LocalNotification("t", "b", NotificationChannelSpec("c", "C"))
        val loud = NotificationOptions(alertOnce = false)

        val droppedInto = RecordingNotifier()
        TwoArgumentOnly(droppedInto).post("a", frame, loud)
        assertTrue(droppedInto.posted.single().options.alertOnce, "the interface default ignores the options silently")

        val keptInto = RecordingNotifier()
        ForwardsBoth(keptInto).post("a", frame, loud)
        assertFalse(keptInto.posted.single().options.alertOnce)
    }

    @Test
    fun `guide id rules - a blank id throws on both overloads two different ids are two notifications`() = runTest {
        val notifier = RecordingNotifier()
        val frame = LocalNotification("t", "b", NotificationChannelSpec("c", "C"))
        assertFailsWith<IllegalArgumentException> { notifier.post("", frame) }
        assertFailsWith<IllegalArgumentException> { notifier.post("   ", frame, NotificationOptions.DEFAULT) }
        notifier.post("Aa", frame)
        notifier.post("BB", frame)
        assertEquals(setOf("Aa", "BB"), notifier.showing.keys)
        notifier.cancel("Aa")
        assertEquals(setOf("BB"), notifier.showing.keys)
        notifier.cancelAll()
        assertTrue(notifier.showing.isEmpty())
        assertEquals(1, notifier.cancelAllCount)
    }

    // ----- 04-api-reference: validations, noOpNotifier, isPosted -----

    @Test
    fun `api reference validations - every documented throw and nothing else throws`() {
        assertFailsWith<IllegalArgumentException> { NotificationChannelSpec(id = " ", name = "n") }
        assertFailsWith<IllegalArgumentException> { NotificationChannelSpec(id = "i", name = "") }
        NotificationChannelSpec(id = "i", name = "n", description = "")
        assertFailsWith<IllegalArgumentException> { NotificationSound.Custom(" ") }
        assertFailsWith<IllegalArgumentException> { NotificationAction(id = "", label = "L") }
        NotificationAction(id = "a", label = "")
        assertFailsWith<IllegalArgumentException> { NotificationConfig(actionBroadcastAction = " ") }
        assertFailsWith<IllegalArgumentException> { NotificationConfig(progressBucketPercent = 0) }
        assertFailsWith<IllegalArgumentException> { NotificationConfig(progressBucketPercent = 101) }
        assertFailsWith<IllegalArgumentException> { NotificationConfig(minProgressInterval = (-1).milliseconds) }
        NotificationConfig(progressBucketPercent = 1)
        NotificationConfig(progressBucketPercent = 100)
        NotificationConfig(minProgressInterval = 0.milliseconds)
        assertNull(NotificationConfig().actionBroadcastAction)
    }

    @Test
    fun `api reference noOpNotifier and isPosted - NotificationsDisabled blank id still throws isPosted is true for Posted only`() = runTest {
        val noOp: Notifier = noOpNotifier()
        val frame = LocalNotification("t", "b", NotificationChannelSpec("c", "C"))
        assertEquals(NotificationResult.NotificationsDisabled, noOp.post("id", frame))
        assertEquals(NotificationResult.NotificationsDisabled, noOp.post("id", frame, NotificationOptions.DEFAULT))
        noOp.cancel("id")
        noOp.cancelAll()
        assertFailsWith<IllegalArgumentException> { noOp.post("", frame) }
        val all: List<NotificationResult> = listOf(
            NotificationResult.Posted, NotificationResult.Coalesced, NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled, NotificationResult.ChannelBlocked("c"), NotificationResult.Failed(null),
        )
        assertEquals(listOf(true, false, false, false, false, false), all.map { it.isPosted })
    }

    // ----- 06-testing: the double's documented behaviour -----

    @Test
    fun `testing doc - posted records every attempt showing only Posted Coalesced keeps the previous frame clear resets`() = runTest {
        val notifier = RecordingNotifier()
        val channel = NotificationChannelSpec("c", "C")
        notifier.post("id", LocalNotification("one", "b", channel))
        notifier.result = NotificationResult.Coalesced
        notifier.post("id", LocalNotification("two", "b", channel))
        notifier.result = NotificationResult.PermissionDenied
        notifier.post("other", LocalNotification("three", "b", channel))
        assertEquals(3, notifier.posted.size)
        assertEquals(listOf("id"), notifier.showing.keys.toList())
        assertEquals("one", notifier.showing.getValue("id").title)
        notifier.clear()
        assertTrue(notifier.posted.isEmpty())
        assertTrue(notifier.showing.isEmpty())
        assertEquals(0, notifier.cancelAllCount)
        assertEquals(NotificationResult.PermissionDenied, notifier.result, "clear leaves the configured result alone")
    }

    @Test
    fun `testing doc - recordings are snapshots equal to what was passed`() = runTest {
        val notifier = RecordingNotifier()
        val channel = NotificationChannelSpec("c", "C")
        val extras: MutableMap<String, String> = mutableMapOf("k" to "1")
        val buttons: MutableList<NotificationAction> = mutableListOf(NotificationAction("a", "A"))
        val frame = LocalNotification("t", "b", channel, actions = buttons, contentExtras = extras)
        notifier.post("id", frame)
        extras["k"] = "2"
        buttons += NotificationAction("b", "B")
        assertEquals(mapOf("k" to "1"), notifier.posted.single().notification.contentExtras)
        assertEquals(1, notifier.posted.single().notification.actions.size)
        assertEquals(1, notifier.showing.getValue("id").actions.size)
    }
}
