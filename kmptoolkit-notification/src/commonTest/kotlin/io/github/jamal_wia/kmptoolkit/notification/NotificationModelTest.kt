package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the model types promise before anything platform-specific is involved: the defaults a caller
 * gets for free, and the values they are stopped from constructing at all.
 *
 * Validating in `init` rather than at post time is deliberate — a blank channel name renders as an
 * empty row in Android's system settings, which is the kind of defect that ships.
 */
class NotificationModelTest {

    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")

    @Test
    fun `a notification defaults to a plain dismissible notification`() {
        val notification = LocalNotification(title = "T", body = "B", channel = channel)

        assertEquals(NotificationIcon.Default, notification.icon)
        assertNull(notification.progress)
        assertEquals(false, notification.ongoing)
        assertTrue(notification.autoCancel)
        assertEquals(emptyList(), notification.actions)
        assertNull(notification.iosCategoryId)
        assertNull(notification.contentExtras, "a notification is not tappable-to-open by default")
    }

    @Test
    fun `a channel defaults to the ordinary importance and the platform sound`() {
        assertEquals(NotificationImportance.Default, channel.importance)
        assertEquals(NotificationSound.Default, channel.sound)
        assertEquals("", channel.description)
    }

    @Test
    fun `a blank channel id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationChannelSpec(id = "   ", name = "Downloads")
        }
    }

    @Test
    fun `a blank channel name is rejected because the user would see the empty row`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationChannelSpec(id = "downloads", name = "")
        }
    }

    @Test
    fun `a blank action id is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationAction(id = " ", label = "Cancel")
        }
    }

    @Test
    fun `an empty action label is allowed because the platform decides how to render it`() {
        assertEquals("", NotificationAction(id = "cancel", label = "").label)
    }

    @Test
    fun `a blank custom sound name is rejected`() {
        assertFailsWith<IllegalArgumentException> { NotificationSound.Custom("") }
    }

    @Test
    fun `isPosted is true only for Posted`() {
        assertTrue(NotificationResult.Posted.isPosted)
        assertEquals(false, NotificationResult.Coalesced.isPosted)
        assertEquals(false, NotificationResult.PermissionDenied.isPosted)
        assertEquals(false, NotificationResult.NotificationsDisabled.isPosted)
        assertEquals(false, NotificationResult.ChannelBlocked("downloads").isPosted)
        assertEquals(false, NotificationResult.Failed(null).isPosted)
    }

    @Test
    fun `a blocked channel result names the channel`() {
        val result: NotificationResult = NotificationResult.ChannelBlocked("downloads")

        assertEquals("downloads", (result as NotificationResult.ChannelBlocked).channelId)
    }
}
