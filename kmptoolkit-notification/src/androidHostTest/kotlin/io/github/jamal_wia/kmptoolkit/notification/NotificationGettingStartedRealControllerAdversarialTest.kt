package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.createPermissionController
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 15 (documentation as a test): 02-getting-started step 3 spells out the real wiring, which every
 * earlier docs test replaced by a fake controller: `createPermissionController(context = this, storage =
 * createKeyValueStorage(this))` handed to `createNotifier(context = this, permissions = permissions)`.
 * This runs those exact lines and the documented step 4: "You can post before asking - you will simply
 * get `NotificationResult.PermissionDenied` back". Also the `NotificationActionIntent` sentence "return
 * `null` for an intent that did not come from this module" against an intent that carries the extra
 * names with the wrong type.
 */
@RunWith(AndroidJUnit4::class)
class NotificationGettingStartedRealControllerAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val channel = NotificationChannelSpec(id = "docs", name = "Docs")

    @Test
    @Config(sdk = [34])
    fun `step 3 and 4 with the real permission controller - a post before the grant is PermissionDenied and shows nothing`() {
        val permissions: PermissionController = createPermissionController(
            context = app,
            storage = createKeyValueStorage(app),
        )
        val notifier: Notifier = createNotifier(context = app, permissions = permissions)

        val result: NotificationResult = runBlocking { notifier.post("reminder-42", LocalNotification("t", "b", channel)) }

        assertEquals(NotificationResult.PermissionDenied, result, "documented in step 4, real controller, no grant")
        val manager: NotificationManager = app.getSystemService(NotificationManager::class.java)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertTrue(runBlocking { permissions.check(CommonPermission.Notifications) }.let { !it.isUsableForTest() })
    }

    @Test
    @Config(sdk = [32])
    fun `below API 33 the same real wiring posts without any grant, as the platform notes say`() {
        val permissions: PermissionController = createPermissionController(context = app, storage = createKeyValueStorage(app))
        val notifier: Notifier = createNotifier(context = app, permissions = permissions)

        val result: NotificationResult = runBlocking { notifier.post("reminder-42", LocalNotification("t", "b", channel)) }

        assertEquals(NotificationResult.Posted, result)
        val manager: NotificationManager = app.getSystemService(NotificationManager::class.java)
        assertEquals(listOf("t"), shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE) })
    }

    @Test
    fun `NotificationActionIntent answers null for a plain intent and for extras of the wrong type, never an exception`() {
        val plain = Intent("any")
        val wrongType = Intent("any")
            .putExtra(NotificationActionIntent.EXTRA_ACTION_ID, 7)
            .putExtra(NotificationActionIntent.EXTRA_NOTIFICATION_ID, byteArrayOf(1, 2))
        val strings = Intent("any")
            .putExtra(NotificationActionIntent.EXTRA_ACTION_ID, "cancel")
            .putExtra(NotificationActionIntent.EXTRA_NOTIFICATION_ID, "download")

        assertNull(runCatching { NotificationActionIntent.actionId(plain) }.getOrElse { throw AssertionError("threw $it") })
        assertNull(NotificationActionIntent.notificationId(plain))
        assertNull(runCatching { NotificationActionIntent.actionId(wrongType) }.getOrElse { throw AssertionError("threw $it") })
        assertNull(runCatching { NotificationActionIntent.notificationId(wrongType) }.getOrElse { throw AssertionError("threw $it") })
        assertEquals("cancel", NotificationActionIntent.actionId(strings))
        assertEquals("download", NotificationActionIntent.notificationId(strings))
        assertEquals(app.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION", NotificationActionIntent.defaultAction(app))
    }

    private fun io.github.jamal_wia.kmptoolkit.permission.PermissionStatus.isUsableForTest(): Boolean =
        this == io.github.jamal_wia.kmptoolkit.permission.PermissionStatus.Granted ||
            this == io.github.jamal_wia.kmptoolkit.permission.PermissionStatus.Limited
}
