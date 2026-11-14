package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Mutation survivor hunt (round 13). Contract (NotificationSound.Custom on Android 8+): the bundled sound belongs to the
 * channel and is a NOTIFICATION sound: it plays on the notification stream (usage notification, content type
 * sonification) so it follows the user's notification volume and Do Not Disturb, never the alarm or media stream.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 34])
class CustomChannelSoundAttributesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a custom channel sound plays as a notification sound at the named raw resource`() {
        NotificationChannels.ensure(
            context,
            NotificationChannelSpec(id = "chime", name = "Chime", sound = NotificationSound.Custom("chime_tone")),
        )
        val channel = assertNotNull(
            context.getSystemService(NotificationManager::class.java).getNotificationChannel("chime"),
        )
        val attributes: AudioAttributes = assertNotNull(channel.audioAttributes)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
        assertEquals("android.resource://${context.packageName}/raw/chime_tone", channel.sound.toString())
    }
}
