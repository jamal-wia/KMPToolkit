package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Mutation survivor (round 22): `NotificationChannels.delete` returns early below API 26, the first level that has
 * channels. On API 26 itself the channel must really be deleted, and only the channel named; a deletion of an id that
 * does not exist is a no-op.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class NotificationChannelsDeleteAtApiBorderAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `delete on API 26 removes exactly the named channel`() {
        NotificationChannels.ensure(context, NotificationChannelSpec(id = "keep", name = "Keep"))
        NotificationChannels.ensure(context, NotificationChannelSpec(id = "drop", name = "Drop"))
        assertNotNull(manager.getNotificationChannel("drop"))

        NotificationChannels.delete(context, "drop")
        NotificationChannels.delete(context, "never-created")

        assertNull(manager.getNotificationChannel("drop"))
        assertNotNull(manager.getNotificationChannel("keep"))
    }
}
