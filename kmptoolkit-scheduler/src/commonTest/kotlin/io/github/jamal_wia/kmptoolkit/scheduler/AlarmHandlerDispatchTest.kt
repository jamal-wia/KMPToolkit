package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers [handlerFor] — the "route a fired alarm to its owner" rule, which is the only dispatch
 * logic the module has that is not a platform API call.
 */
class AlarmHandlerDispatchTest {

    private class FakeHandler(override val type: String) : AlarmHandler {
        override suspend fun onFire(alarm: ScheduledAlarm): Unit = Unit
    }

    @Test
    fun `handlerFor returns the handler registered for the matching type`() {
        val reminder = FakeHandler("REMINDER")
        val digest = FakeHandler("DAILY_DIGEST")

        val resolved: AlarmHandler? = listOf(reminder, digest).handlerFor("DAILY_DIGEST")

        assertSame(digest, resolved)
    }

    @Test
    fun `handlerFor returns null for an unknown type`() {
        val resolved: AlarmHandler? = listOf(FakeHandler("REMINDER")).handlerFor("UNKNOWN_TYPE")

        assertNull(resolved)
    }

    @Test
    fun `handlerFor returns null against an empty handler collection`() {
        val resolved: AlarmHandler? = emptyList<AlarmHandler>().handlerFor("REMINDER")

        assertNull(resolved)
    }

    @Test
    fun `handlerFor picks the first registration when two handlers share a type`() {
        val first = FakeHandler("DUPLICATE")
        val second = FakeHandler("DUPLICATE")

        val resolved: AlarmHandler? = listOf(first, second).handlerFor("DUPLICATE")

        assertSame(first, resolved)
    }

    @Test
    fun `handlerFor is case-sensitive on the type key`() {
        val resolved: AlarmHandler? = listOf(FakeHandler("Reminder")).handlerFor("reminder")

        assertNull(resolved)
    }
}
