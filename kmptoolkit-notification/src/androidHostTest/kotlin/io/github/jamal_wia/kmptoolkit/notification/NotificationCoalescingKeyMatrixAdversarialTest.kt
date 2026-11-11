package io.github.jamal_wia.kmptoolkit.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "The bucket applies only to frames that differ from the one showing in nothing but the percentage;
 * a changed title, body, button set or options bypasses it" (docs 04, Coalescing; docs 03: "title,
 * body, buttons or options"). Read as a matrix over every field of `LocalNotification` and
 * `NotificationOptions`: with the rate limit off, a frame that differs from the showing one in that
 * field alone, inside the same bucket, must post, while the very same frame minus the difference is
 * redundant (the control that makes the probe mean something).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationCoalescingKeyMatrixAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    private val baseChannel = NotificationChannelSpec(id = "matrix", name = "Matrix", description = "d")
    private val pause = NotificationAction("pause", "Pause")
    private val stop = NotificationAction("stop", "Stop")

    private fun base(percent: Int): LocalNotification = LocalNotification(
        title = "title", body = "body", channel = baseChannel,
        icon = NotificationIcon.Default, progress = NotificationProgress.Determinate(percent),
        ongoing = true, autoCancel = false, actions = listOf(pause, stop), iosCategoryId = "cat",
        contentExtras = mapOf("screen" to "downloads"),
    )

    private val baseOptions = NotificationOptions(
        alertOnce = true, dismissAction = NotificationAction("dismiss", "x"), mediaStyle = false,
        actionIcons = mapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause)),
    )

    private class Variant(val name: String, val notification: (LocalNotification) -> LocalNotification, val options: (NotificationOptions) -> NotificationOptions = { it })

    private val variants: List<Variant> = listOf(
        Variant("title", { it.copy(title = "other") }),
        Variant("body", { it.copy(body = "other") }),
        Variant("channel id", { it.copy(channel = it.channel.copy(id = "matrix-2")) }),
        Variant("channel name", { it.copy(channel = it.channel.copy(name = "Other")) }),
        Variant("channel description", { it.copy(channel = it.channel.copy(description = "other")) }),
        Variant("channel importance", { it.copy(channel = it.channel.copy(importance = NotificationImportance.High)) }),
        Variant("channel sound", { it.copy(channel = it.channel.copy(sound = NotificationSound.Silent)) }),
        Variant("icon", { it.copy(icon = NotificationIcon.AndroidDrawable(android.R.drawable.ic_dialog_alert)) }),
        Variant("ongoing", { it.copy(ongoing = false) }),
        Variant("autoCancel", { it.copy(autoCancel = true) }),
        Variant("button label", { it.copy(actions = listOf(NotificationAction("pause", "Hold"), stop)) }),
        Variant("button order", { it.copy(actions = listOf(stop, pause)) }),
        Variant("extra button", { it.copy(actions = it.actions + NotificationAction("more", "More")) }),
        Variant("no buttons", { it.copy(actions = emptyList()) }),
        Variant("ios category", { it.copy(iosCategoryId = "other") }),
        Variant("tap extras value", { it.copy(contentExtras = mapOf("screen" to "settings")) }),
        Variant("tap extras removed", { it.copy(contentExtras = null) }),
        Variant("tap extras empty map", { it.copy(contentExtras = emptyMap()) }),
        Variant("alertOnce", { it }, { NotificationOptions(false, it.dismissAction, it.mediaStyle, it.actionIcons) }),
        Variant("dismiss action", { it }, { NotificationOptions(it.alertOnce, NotificationAction("dismiss", "other"), it.mediaStyle, it.actionIcons) }),
        Variant("dismiss action removed", { it }, { NotificationOptions(it.alertOnce, null, it.mediaStyle, it.actionIcons) }),
        Variant("mediaStyle", { it }, { NotificationOptions(it.alertOnce, it.dismissAction, true, it.actionIcons) }),
        Variant("action icons", { it }, { NotificationOptions(it.alertOnce, it.dismissAction, it.mediaStyle, mapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_play))) }),
        Variant("action icons removed", { it }, { NotificationOptions(it.alertOnce, it.dismissAction, it.mediaStyle, emptyMap()) }),
    )

    private fun post(n: Notifier, notification: LocalNotification, options: NotificationOptions): NotificationResult =
        runBlocking { n.post("d", notification, options) }

    @Test
    fun `a frame that differs in any one field from the one showing is posted inside the same bucket, and is redundant without the difference`() {
        variants.forEach { variant: Variant ->
            val n: Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))
            assertEquals(NotificationResult.Posted, post(n, base(20), baseOptions), variant.name)
            // The control: the same frame, one percent on, nothing else changed.
            assertEquals(NotificationResult.Coalesced, post(n, base(21), baseOptions), "control for ${variant.name}")

            val result: NotificationResult = post(n, variant.notification(base(22)), variant.options(baseOptions))

            assertEquals(NotificationResult.Posted, result, "a changed ${variant.name} was treated as redundant")
        }
    }
}
