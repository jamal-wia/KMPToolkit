package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Random ids drawn from an alphabet of awkward but valid code points (controls, noncharacters, private use,
 * format characters, combining marks, supplementary characters as proper pairs; never an unpaired surrogate):
 * every distinct id keeps its own notification, tap target and button.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRandomIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private val alphabet: List<String> = listOf(
        "a", "A", "z", "0", "9", "_", "-", ".", "~", "!", "'", "(", ")", "*", "%", "/", "\\", ":", ";", "@", "?", "#", "&", "=", "+", " ", "\t", "\n", "\r",
        "\u0000", "\u0001", "\u001F", "\u007F", "\u0080", "\u009F", " ", "­", "​", "‍", "‎", "‏", " ", "‮", "﻿",
        "�", "￾", "￿", "", "", "́", "̈", "é", "İ", "ı", "ß", "ﬁ", "Å", "Å",
        "😀", "👍", "🇩🇪", "𝄞", "􏿿", "𐀀", "中", "ا", "Ａ",
    )

    @Test
    fun `random awkward ids stay distinct through tray, tap target and button`() = runTest {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        val notifier: Notifier = createNotifier(
            context, FakePermissionController(defaultStatus = PermissionStatus.Granted), NotificationConfig(minProgressInterval = Duration.ZERO),
        )
        val random = Random(20261108)
        val ids: List<String> = generateSequence { (1..random.nextInt(1, 6)).joinToString("") { alphabet[random.nextInt(alphabet.size)] } }
            .filter { it.isNotBlank() }.distinct().take(1_500).toList()
        ids.forEachIndexed { index: Int, id: String ->
            notifier.post(
                id,
                LocalNotification(
                    title = "n$index", body = "b", channel = NotificationChannelSpec(id = "c", name = "C"),
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
            val got: String? = shadowOf(requireNotNull(n.contentIntent)).savedIntent.getStringExtra("which")
            if (got != "n$index") failures += "tap of ${id.map { it.code }} opens $got"
            if (NotificationActionIntent.notificationId(shadowOf(n.actions.single().actionIntent).savedIntent) != id) {
                failures += "button of ${id.map { it.code }} reports another id"
            }
        }
        assertEquals(emptyList(), failures.take(5))
    }
}
