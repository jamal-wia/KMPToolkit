package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.runBlocking

/**
 * Round 12: every public constructor of the notification model with every bad argument of the grid
 * (zero, negative, Int and Long extremes, blank strings of every kind, huge collections). A bad value
 * must fail at construction with the documented IllegalArgumentException (also through copy), and a
 * value the contract accepts must construct, compare, hash and print however large it is.
 */
class NotificationBadArgumentsSweepAdversarialTest {

    private val blanks: List<String> = listOf(
        "", " ", "  ", "\t", "\n", "\r\n", "\u000B", "\u000C", " ", " ", " ", " ", "　", " \t\n ",
    )

    // A zero width space is not whitespace to Kotlin: documented as a real id elsewhere (round 7), so it is not in the grid.
    private val channel = NotificationChannelSpec("c", "C")

    @Test
    fun `every blank string is rejected as a channel id a channel name an action id and a custom sound`() {
        for (blank in blanks) {
            assertFailsWith<IllegalArgumentException>("channel id <$blank>") { NotificationChannelSpec(id = blank, name = "n") }
            assertFailsWith<IllegalArgumentException>("channel name <$blank>") { NotificationChannelSpec(id = "i", name = blank) }
            assertFailsWith<IllegalArgumentException>("action id <$blank>") { NotificationAction(id = blank, label = "l") }
            assertFailsWith<IllegalArgumentException>("custom sound <$blank>") { NotificationSound.Custom(blank) }
            assertFailsWith<IllegalArgumentException>("broadcast action <$blank>") { NotificationConfig(actionBroadcastAction = blank) }
        }
    }

    @Test
    fun `a blank action label a blank description blank text and a blank category are allowed`() {
        for (blank in blanks) {
            NotificationAction(id = "a", label = blank)
            NotificationChannelSpec(id = "i", name = "n", description = blank)
            LocalNotification(title = blank, body = blank, channel = channel, iosCategoryId = blank)
        }
    }

    @Test
    fun `progressBucketPercent accepts exactly 1 to 100`() {
        val bad: List<Int> = listOf(Int.MIN_VALUE, -100, -1, 0, 101, 102, 1_000, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        for (value in bad) {
            assertFailsWith<IllegalArgumentException>("bucket $value") { NotificationConfig(progressBucketPercent = value) }
        }
        for (value in 1..100) assertEquals(value, NotificationConfig(progressBucketPercent = value).progressBucketPercent)
        val config = NotificationConfig()
        for (value in bad) {
            assertFailsWith<IllegalArgumentException>("copy bucket $value") { config.copy(progressBucketPercent = value) }
        }
    }

    @Test
    fun `minProgressInterval rejects every negative duration and accepts zero up to infinite`() {
        val negative: List<Duration> = listOf(
            (-1).nanoseconds, (-1_000).nanoseconds, (-1).hours, Duration.INFINITE.unaryMinus(), Duration.parseIsoString("-PT1S"),
        )
        for (value in negative) {
            assertFailsWith<IllegalArgumentException>("interval $value") { NotificationConfig(minProgressInterval = value) }
            assertFailsWith<IllegalArgumentException>("copy interval $value") { NotificationConfig().copy(minProgressInterval = value) }
        }
        listOf(Duration.ZERO, 1.nanoseconds, 1.hours, Duration.INFINITE).forEach {
            assertEquals(it, NotificationConfig(minProgressInterval = it).minProgressInterval)
        }
    }

    @Test
    fun `progress values far outside 0 to 100 construct and compare`() {
        val extremes: List<Int> = listOf(Int.MIN_VALUE, -1, 0, 100, 101, Int.MAX_VALUE)
        extremes.forEach { percent ->
            val a = NotificationProgress.Determinate(percent)
            assertEquals(a, NotificationProgress.Determinate(percent))
            assertEquals(a.hashCode(), NotificationProgress.Determinate(percent).hashCode())
            assertNotEquals<NotificationProgress>(a, NotificationProgress.Indeterminate)
            LocalNotification("t", "b", channel, progress = a)
        }
    }

    @Test
    fun `a notification with a hundred thousand buttons and extras constructs compares hashes and prints`() {
        val actions: List<NotificationAction> = (0 until 100_000).map { NotificationAction("id-$it", "label-$it") }
        val extras: Map<String, String> = (0 until 100_000).associate { "k$it" to "v$it" }
        val a = LocalNotification("t".repeat(1_000_000), "b", channel, actions = actions, contentExtras = extras)
        val b = a.copy(actions = actions.toList(), contentExtras = extras.toMap())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().isNotEmpty())
        assertNotEquals(a, a.copy(contentExtras = extras + ("extra" to "x")))
        assertNotEquals(a, a.copy(contentExtras = null))
        assertNotEquals(a.copy(contentExtras = null), a.copy(contentExtras = emptyMap()), "null extras and empty extras are different documented states")
    }

    @Test
    fun `NotificationOptions with a huge action icon map compares by value and its hash agrees`() {
        val icons: Map<String, NotificationIcon> = (0 until 50_000).associate { "a$it" to NotificationIcon.AndroidDrawable(it) }
        val a = NotificationOptions(actionIcons = icons)
        val b = NotificationOptions(actionIcons = icons.toMap())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, NotificationOptions(actionIcons = icons + ("z" to NotificationIcon.Default)))
        assertNotEquals(NotificationOptions(alertOnce = false), NotificationOptions.DEFAULT)
        assertEquals(NotificationIcon.AndroidDrawable(Int.MIN_VALUE), NotificationIcon.AndroidDrawable(Int.MIN_VALUE))
    }

    @Test
    fun `the no-op notifier rejects every blank id on every form of post and never throws on cancel`() {
        val notifier: Notifier = noOpNotifier()
        for (blank in blanks) {
            assertFailsWith<IllegalArgumentException>("post <$blank>") {
                runBlocking { notifier.post(blank, LocalNotification("t", "b", channel)) }
            }
            assertFailsWith<IllegalArgumentException>("post with options <$blank>") {
                runBlocking { notifier.post(blank, LocalNotification("t", "b", channel), NotificationOptions.DEFAULT) }
            }
            notifier.cancel(blank)
        }
        notifier.cancel("x".repeat(1_000_000))
        notifier.cancelAll()
        assertEquals(
            NotificationResult.NotificationsDisabled,
            runBlocking { notifier.post("x".repeat(1_000_000), LocalNotification("t", "b", channel)) },
        )
    }
}
