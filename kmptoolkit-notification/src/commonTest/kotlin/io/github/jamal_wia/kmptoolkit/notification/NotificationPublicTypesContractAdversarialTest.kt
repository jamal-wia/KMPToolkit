package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * equals, hashCode and toString of every public value type of the module, and the exhaustiveness of
 * its sealed types, on both platforms. The `when` expressions below have no `else` on purpose: a new
 * subtype of a sealed type is a compile error here, so adding one is never silent (docs 04 list the
 * complete sets a consumer may match on).
 */
class NotificationPublicTypesContractAdversarialTest {

    private fun describe(result: NotificationResult): String = when (result) {
        NotificationResult.Posted -> "posted"
        NotificationResult.Coalesced -> "coalesced"
        NotificationResult.PermissionDenied -> "denied"
        NotificationResult.NotificationsDisabled -> "disabled"
        is NotificationResult.ChannelBlocked -> "blocked:${result.channelId}"
        is NotificationResult.Failed -> "failed"
    }

    private fun describe(progress: NotificationProgress): String = when (progress) {
        NotificationProgress.Indeterminate -> "indeterminate"
        is NotificationProgress.Determinate -> "determinate:${progress.percent}"
    }

    private fun describe(icon: NotificationIcon): String = when (icon) {
        NotificationIcon.Default -> "default"
        is NotificationIcon.AndroidDrawable -> "drawable:${icon.resourceId}"
    }

    private fun describe(sound: NotificationSound): String = when (sound) {
        NotificationSound.Silent -> "silent"
        NotificationSound.Default -> "default"
        is NotificationSound.Custom -> "custom:${sound.resourceName}"
    }

    private fun describe(importance: NotificationImportance): String = when (importance) {
        NotificationImportance.Low -> "low"
        NotificationImportance.Default -> "default"
        NotificationImportance.High -> "high"
    }

    @Test
    fun `every sealed hierarchy is matched exhaustively and every case answers`() {
        val results: List<NotificationResult> = listOf(
            NotificationResult.Posted,
            NotificationResult.Coalesced,
            NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled,
            NotificationResult.ChannelBlocked("c"),
            NotificationResult.Failed(null),
        )
        assertEquals(
            listOf("posted", "coalesced", "denied", "disabled", "blocked:c", "failed"),
            results.map { describe(it) },
        )
        assertEquals(listOf("indeterminate", "determinate:7"),
            listOf(NotificationProgress.Indeterminate, NotificationProgress.Determinate(7)).map { describe(it) })
        assertEquals(listOf("default", "drawable:5"),
            listOf(NotificationIcon.Default, NotificationIcon.AndroidDrawable(5)).map { describe(it) })
        assertEquals(listOf("silent", "default", "custom:s"),
            listOf(NotificationSound.Silent, NotificationSound.Default, NotificationSound.Custom("s")).map { describe(it) })
        assertEquals(listOf("low", "default", "high"), NotificationImportance.entries.map { describe(it) })
    }

    @Test
    fun `data objects print their simple name and are equal to themselves only`() {
        assertEquals("Posted", NotificationResult.Posted.toString())
        assertEquals("Coalesced", NotificationResult.Coalesced.toString())
        assertEquals("PermissionDenied", NotificationResult.PermissionDenied.toString())
        assertEquals("NotificationsDisabled", NotificationResult.NotificationsDisabled.toString())
        assertEquals("Indeterminate", NotificationProgress.Indeterminate.toString())
        assertEquals("Default", NotificationIcon.Default.toString())
        assertEquals("Silent", NotificationSound.Silent.toString())
        assertNotEquals<Any>(NotificationResult.Posted, NotificationResult.Coalesced)
        assertNotEquals<Any>(NotificationSound.Silent, NotificationSound.Default)
    }

    @Test
    fun `data classes are equal by value with a matching hash and a readable string`() {
        val cause = IllegalStateException("boom")
        val pairs: List<Pair<Any, Any>> = listOf(
            NotificationResult.ChannelBlocked("c") to NotificationResult.ChannelBlocked("c"),
            NotificationResult.Failed(cause) to NotificationResult.Failed(cause),
            NotificationResult.Failed(null) to NotificationResult.Failed(null),
            NotificationProgress.Determinate(40) to NotificationProgress.Determinate(40),
            NotificationIcon.AndroidDrawable(3) to NotificationIcon.AndroidDrawable(3),
            NotificationSound.Custom("ding") to NotificationSound.Custom("ding"),
            NotificationAction("id", "Label") to NotificationAction("id", "Label"),
            NotificationChannelSpec("i", "n", "d") to NotificationChannelSpec("i", "n", "d"),
            NotificationConfig(progressBucketPercent = 5) to NotificationConfig(progressBucketPercent = 5),
            NotificationOptions(alertOnce = false, actionIcons = mapOf("a" to NotificationIcon.Default)) to
                NotificationOptions(alertOnce = false, actionIcons = mapOf("a" to NotificationIcon.Default)),
            LocalNotification("t", "b", NotificationChannelSpec("i", "n")) to
                LocalNotification("t", "b", NotificationChannelSpec("i", "n")),
        )
        pairs.forEach { (left: Any, right: Any) ->
            assertEquals(left, right, "$left")
            assertEquals(right, left, "$left (symmetry)")
            assertEquals(left.hashCode(), right.hashCode(), "$left (hash)")
            assertEquals(left.toString(), right.toString())
            assertTrue(left.toString().isNotEmpty())
        }
    }

    @Test
    fun `values that differ in one field are not equal`() {
        val channel = NotificationChannelSpec("i", "n")
        val base = LocalNotification("t", "b", channel)
        listOf(
            base.copy(title = "t2"), base.copy(body = "b2"),
            base.copy(channel = NotificationChannelSpec("i2", "n")),
            base.copy(icon = NotificationIcon.AndroidDrawable(1)),
            base.copy(progress = NotificationProgress.Indeterminate),
            base.copy(ongoing = true), base.copy(autoCancel = false),
            base.copy(actions = listOf(NotificationAction("x", "X"))),
            base.copy(iosCategoryId = "cat"), base.copy(contentExtras = emptyMap()),
        ).forEach { other: LocalNotification -> assertNotEquals(base, other, "$other") }
        assertNotEquals(NotificationOptions(), NotificationOptions(alertOnce = false))
        assertNotEquals(NotificationOptions(), NotificationOptions(mediaStyle = true))
        assertNotEquals(NotificationOptions(), NotificationOptions(dismissAction = NotificationAction("d", "D")))
        assertNotEquals(NotificationOptions(), NotificationOptions(actionIcons = mapOf("a" to NotificationIcon.Default)))
        assertNotEquals(NotificationConfig(), NotificationConfig(minProgressInterval = NotificationConfig.DEFAULT_MIN_PROGRESS_INTERVAL * 2))
        assertNotEquals(NotificationConfig(), NotificationConfig(actionBroadcastAction = "x"))
    }

    @Test
    fun `a notification with a huge payload prints without failing and the string names its type`() {
        val huge = "x".repeat(200_000)
        val notification = LocalNotification(
            title = huge,
            body = huge,
            channel = NotificationChannelSpec("i", "n"),
            actions = List(50) { NotificationAction("a$it", "L$it") },
            contentExtras = (0 until 50).associate { "k$it" to "v$it" },
        )
        val text: String = notification.toString()
        assertTrue(text.startsWith("LocalNotification("), text.take(40))
        assertEquals(notification.hashCode(), notification.copy().hashCode())
        assertEquals(notification, notification.copy())
    }

    @Test
    fun `the options type has the same value semantics as its siblings and DEFAULT equals a fresh instance`() {
        assertEquals(NotificationOptions.DEFAULT, NotificationOptions())
        assertEquals(NotificationOptions.DEFAULT.hashCode(), NotificationOptions().hashCode())
        val text: String = NotificationOptions.DEFAULT.toString()
        assertTrue(text.startsWith("NotificationOptions("), text)
        assertTrue(text.contains("alertOnce=true"), text)
        assertNotEquals<Any?>(NotificationOptions.DEFAULT, null)
        assertNotEquals<Any?>(NotificationOptions.DEFAULT, "NotificationOptions")
    }
}
