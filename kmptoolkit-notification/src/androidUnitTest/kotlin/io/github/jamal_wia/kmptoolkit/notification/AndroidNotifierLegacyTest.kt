package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.RecordingPermissionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The API 24–25 path, which has no notification channels at all.
 *
 * It needs its own class because the module-wide `robolectric.properties` pins `sdk=35`, so nothing
 * else in the suite ever executes a pre-26 branch — and `minSdk` for this repository is 24, which
 * makes that branch real for consumers even though it is invisible to every other test here. A
 * class-level `@Config` overrides the properties file.
 *
 * What changes below 26: the sound belongs to the notification rather than to a channel, and
 * `NotificationCompat` plays **nothing** unless a sound is set explicitly — so
 * [NotificationSound.Default] has to be spelled out or it silently means "silent" on exactly the
 * levels nobody runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24])
class AndroidNotifierLegacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shown: List<Notification>
        get() = shadowOf(manager).allNotifications

    private val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

    private fun notifier(): Notifier = createNotifier(
        context,
        permissions,
        NotificationConfig(minProgressInterval = Duration.ZERO),
    )

    private fun notification(
        importance: NotificationImportance = NotificationImportance.Default,
        sound: NotificationSound = NotificationSound.Default,
    ): LocalNotification = LocalNotification(
        title = "Title",
        body = "Body",
        channel = NotificationChannelSpec(
            id = "downloads",
            name = "Downloads",
            importance = importance,
            sound = sound,
        ),
    )

    @Test
    fun `posting works where there are no channels`() = runTest {
        val result: NotificationResult = notifier().post("download", notification())

        assertEquals(NotificationResult.Posted, result)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a Default sound plays the platform default rather than nothing`() = runTest {
        notifier().post("download", notification())

        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, shown.single().sound)
    }

    @Test
    fun `a Custom sound resolves to the raw resource`() = runTest {
        notifier().post("download", notification(sound = NotificationSound.Custom("chat_message")))

        assertTrue(
            shown.single().sound.toString().contains("raw/chat_message"),
            "expected a res/raw uri, was ${shown.single().sound}",
        )
    }

    @Test
    fun `a Silent sound plays nothing`() = runTest {
        notifier().post("download", notification(sound = NotificationSound.Silent))

        assertNull(shown.single().sound)
    }

    @Test
    fun `a Low importance stays silent as it would be on a channel`() = runTest {
        // IMPORTANCE_LOW is silent from API 26 on; a progress notification must not start making a
        // noise just because the device is older.
        notifier().post(
            "download",
            notification(importance = NotificationImportance.Low, sound = NotificationSound.Default),
        )

        assertNull(shown.single().sound)
    }

    @Test
    fun `a channel can never be reported as blocked where channels do not exist`() = runTest {
        assertEquals(NotificationResult.Posted, notifier().post("download", notification()))
    }

    @Test
    fun `progress coalescing behaves the same as it does above 26`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(40)))

        val result: NotificationResult = notifier.post(
            "d",
            notification().copy(progress = NotificationProgress.Determinate(41)),
        )

        assertEquals(NotificationResult.Coalesced, result)
    }
}
