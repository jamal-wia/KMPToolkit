package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What this module's manifest puts into a consumer's app, read from a real package manager (the
 * scheduler had no such test; the notification module has one for permissions only). Three promises
 * of the manifest comment and of the library invariants: no permission is merged in, the alarm
 * receiver is private (`exported="false"`, so only this module's `PendingIntent`s can reach it) and it
 * listens to no system broadcast (no intent filter, so a reboot, a time or zone change or an app
 * update never reaches `onReceive`, and the guide's "re-arm at boot" stays the app's job).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryManifestAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val packageManager: PackageManager get() = context.packageManager

    private fun info(flags: Int): PackageInfo = packageManager.getPackageInfo(context.packageName, flags)

    private fun receiverInfo(): ActivityInfo? =
        info(PackageManager.GET_RECEIVERS).receivers.orEmpty().firstOrNull { it.name == RECEIVER_CLASS }

    private fun receiversOf(intent: Intent): List<ResolveInfo> =
        packageManager.queryBroadcastReceivers(intent, 0)

    @Test
    fun `no permission of the scheduler reaches a consumer`() {
        val declared: List<String> = info(PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
        val harness: Set<String> = setOf("android.permission.REORDER_TASKS", "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        assertEquals(emptyList(), declared - harness, "the library must merge no permission into a consumer's manifest")
        for (suspect in listOf(
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WAKE_LOCK",
            "android.permission.POST_NOTIFICATIONS",
        )) {
            assertFalse(suspect in declared, "$suspect is the consumer's to declare")
        }
    }

    @Test
    fun `the alarm receiver is declared, enabled and private`() {
        val receiver: ActivityInfo = assertNotNull(receiverInfo(), "the receiver must be declared by the library, or alarms never arrive")
        assertTrue(receiver.enabled)
        assertFalse(receiver.exported, "an exported receiver lets any app forge an alarm")
        assertEquals(context.packageName, receiver.packageName)
    }

    @Test
    fun `the alarm receiver listens to no system broadcast`() {
        val broadcasts: List<String> = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
        for (action: String in broadcasts) {
            val reached: List<String> = receiversOf(Intent(action).setPackage(context.packageName)).map { it.activityInfo.name }
            assertFalse(RECEIVER_CLASS in reached, "$action reaches the alarm receiver")
        }
    }

    @Test
    fun `an explicit alarm intent does resolve to the receiver`() {
        val reached: List<String> = receiversOf(Intent(context, AlarmReceiver::class.java)).map { it.activityInfo.name }
        assertEquals(listOf(RECEIVER_CLASS), reached)
    }

    @Test
    fun `the library contributes no other component`() {
        val flags: Int = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
        val own: List<String> = with(info(flags)) {
            activities.orEmpty().map { it.name } + services.orEmpty().map { it.name } +
                providers.orEmpty().map { it.name } + receivers.orEmpty().map { it.name }
        }.filter { it.startsWith("io.github.jamal_wia.kmptoolkit") }
        assertEquals(listOf(RECEIVER_CLASS), own)
    }

    private companion object {
        const val RECEIVER_CLASS: String = "io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver"
    }
}
