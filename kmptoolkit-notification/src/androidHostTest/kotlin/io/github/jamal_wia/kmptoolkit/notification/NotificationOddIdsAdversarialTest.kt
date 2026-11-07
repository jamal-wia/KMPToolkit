package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/** Every odd-but-legal id (unpaired surrogates aside) must stay its own notification, with its own tap target and buttons. */
@RunWith(AndroidJUnit4::class)
open class NotificationOddIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private fun notifier(): Notifier = createNotifier(
        context, FakePermissionController(defaultStatus = PermissionStatus.Granted),
        NotificationConfig(minProgressInterval = Duration.ZERO),
    )

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private val ids: List<String> = listOf(
        "a/b", "a%2Fb", "a%252Fb", "a", "a/", "/a", ".", "..", "a/../b", "a b", "a+b", "a?b", "a#b", "a:b",
        "\u0000", "\u0000\u0000", "a\u0000", "😀", "x".repeat(50_000), "x".repeat(50_001),
        "é", "é", "İ", "i̇", "​", "﻿", "%", "%%", "%00", "[a]", "{a}", "a|b", "a\\b", "a\"b",
    )

    @Test
    fun `every odd id is its own notification with its own tap target and button`() = runTest {
        registerLauncher()
        val notifier: Notifier = notifier()
        ids.forEachIndexed { index: Int, id: String ->
            notifier.post(
                id,
                LocalNotification(
                    title = "n$index", body = "b", channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
                    actions = listOf(NotificationAction("stop", "Stop")), contentExtras = mapOf("which" to "n$index"),
                ),
            )
        }
        val byTitle: Map<String, Notification> = shadowOf(manager).allNotifications.associateBy {
            it.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }
        assertEquals(ids.size, byTitle.size)
        val failures: MutableList<String> = mutableListOf()
        ids.forEachIndexed { index: Int, id: String ->
            val n: Notification = byTitle.getValue("n$index")
            val tap: PendingIntent = requireNotNull(n.contentIntent)
            val got: String? = shadowOf(tap).savedIntent.getStringExtra("which")
            if (got != "n$index") failures += "tap #$index (${id.take(12).map { it.code }}) opens n$got"
            val button: Intent = shadowOf(n.actions.single().actionIntent).savedIntent
            if (NotificationActionIntent.notificationId(button) != id) failures += "button #$index reports another id"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `cancelling one odd id leaves every other showing`() = runTest {
        val notifier: Notifier = notifier()
        ids.forEachIndexed { index: Int, id: String ->
            notifier.post(
                id,
                LocalNotification(title = "n$index", body = "b", channel = NotificationChannelSpec(id = "downloads", name = "Downloads")),
            )
        }
        ids.forEachIndexed { index: Int, id: String ->
            notifier.cancel(id)
            assertEquals(ids.size - index - 1, shadowOf(manager).allNotifications.size, "after cancelling #$index")
        }
    }
}

/** The same checks below API 29, where the PendingIntent key rides on a category (or, with no single receiver, on a hash). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class NotificationOddIdsLegacyApiAdversarialTest : NotificationOddIdsAdversarialTest()
