package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 15 (contract lawyer): the interface-level sentences of `Notifier` that no platform
 * implementation can show on its own (runs on the JVM and on iOS).
 *
 * - 04-api-reference: "The interface default ignores `options` and calls the two-argument form, so an
 *   older implementation keeps working".
 * - 03-guide "Common mistakes": "A decorator that forwards only the two-argument `post`. The
 *   three-argument form then falls back to the interface default and drops the options silently."
 * - `noOpNotifier`: "Stateless and shared"; "Posts nothing and reports `NotificationsDisabled`"; and the
 *   blank id "throws IllegalArgumentException" even though it posts nothing (06-testing).
 * - `isPosted` "is true for `Posted` only".
 */
class NotifierInterfaceContractAdversarialTest {

    private val channel = NotificationChannelSpec(id = "c", name = "C")
    private val notification = LocalNotification(title = "t", body = "b", channel = channel)

    /** An implementation written against the interface before the options overload existed. */
    private class OldNotifier(private val answer: NotificationResult) : Notifier {
        val calls: MutableList<Pair<String, LocalNotification>> = mutableListOf()
        override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
            calls += id to notification
            return answer
        }

        override fun cancel(id: String) = Unit
        override fun cancelAll() = Unit
    }

    /** The mistake the guide names: forwards the two-argument form only. */
    private class TwoArgumentDecorator(private val inner: Notifier) : Notifier {
        override suspend fun post(id: String, notification: LocalNotification): NotificationResult = inner.post(id, notification)
        override fun cancel(id: String) = inner.cancel(id)
        override fun cancelAll() = inner.cancelAll()
    }

    private class OptionsAwareNotifier : Notifier {
        val seen: MutableList<NotificationOptions?> = mutableListOf()
        override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
            seen += null
            return NotificationResult.Posted
        }

        override suspend fun post(id: String, notification: LocalNotification, options: NotificationOptions): NotificationResult {
            seen += options
            return NotificationResult.Posted
        }

        override fun cancel(id: String) = Unit
        override fun cancelAll() = Unit
    }

    @Test
    fun `the default options overload calls the two-argument post exactly once with the same id and notification and returns its answer`() = runTest {
        for (answer in listOf(NotificationResult.Posted, NotificationResult.Coalesced, NotificationResult.Failed(null))) {
            val old = OldNotifier(answer)

            val result: NotificationResult = old.post("x", notification, NotificationOptions(alertOnce = false, mediaStyle = true))

            assertEquals(answer, result)
            assertEquals(listOf("x" to notification), old.calls)
        }
    }

    @Test
    fun `a decorator that forwards only the two-argument post hands its inner notifier the plain form as the guide warns`() = runTest {
        val inner = OptionsAwareNotifier()
        val decorator: Notifier = TwoArgumentDecorator(inner)

        decorator.post("x", notification, NotificationOptions(alertOnce = false))
        decorator.post("x", notification)

        assertEquals(listOf<NotificationOptions?>(null, null), inner.seen, "the options were dropped by the inherited default, silently")
    }

    @Test
    fun `an implementation that overrides the options overload receives the options untouched`() = runTest {
        val aware = OptionsAwareNotifier()
        val options = NotificationOptions(alertOnce = false, mediaStyle = true)

        aware.post("x", notification, options)

        assertSame(options, aware.seen.single())
    }

    @Test
    fun `noOpNotifier is one shared stateless instance that disables both forms rejects a blank id in both and cancels silently`() = runTest {
        val first: Notifier = noOpNotifier()
        val second: Notifier = noOpNotifier()

        assertSame(first, second, "documented: stateless and shared")
        assertEquals(NotificationResult.NotificationsDisabled, first.post("x", notification))
        assertEquals(NotificationResult.NotificationsDisabled, first.post("x", notification, NotificationOptions(alertOnce = false)))
        for (blank in listOf("", " ", "\t", "\n  \n")) {
            assertFailsWith<IllegalArgumentException>("blank id <$blank>, two-argument form") { first.post(blank, notification) }
            assertFailsWith<IllegalArgumentException>("blank id <$blank>, options form") { first.post(blank, notification, NotificationOptions.DEFAULT) }
        }
        first.cancel("x")
        first.cancel("")
        first.cancelAll()
        assertEquals(NotificationResult.NotificationsDisabled, first.post("x", notification), "still callable after cancelAll")
    }

    @Test
    fun `isPosted is true for Posted only`() {
        val all: List<NotificationResult> = listOf(
            NotificationResult.Posted,
            NotificationResult.Coalesced,
            NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled,
            NotificationResult.ChannelBlocked("c"),
            NotificationResult.Failed(null),
            NotificationResult.Failed(IllegalStateException("x")),
        )
        assertTrue(all.single { it.isPosted } === NotificationResult.Posted)
        assertFalse(NotificationResult.Coalesced.isPosted)
    }
}
