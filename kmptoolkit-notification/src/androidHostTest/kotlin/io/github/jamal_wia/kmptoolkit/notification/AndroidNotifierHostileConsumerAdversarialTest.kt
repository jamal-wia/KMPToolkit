package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Android twin of `IosNotifierHostileConsumerAdversarialTest`: a controller that throws (the gate is
 * asked from API 33 on), a button list and an extras map that throw when read. A runtime failure is a
 * `Failed` answer, an `Error` escapes but leaves no claim and no held lock behind, at every level.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 33, 35])
class AndroidNotifierHostileConsumerAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private class Throwing(val failure: () -> Throwable) : PermissionController {
        var armed: Boolean = true
        override suspend fun check(permission: Permission): PermissionStatus {
            if (armed) throw failure()
            return PermissionStatus.Granted
        }

        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    @Before
    fun clean() {
        manager.cancelAll()
        shadowOf(manager).setNotificationsEnabled(true)
    }

    private val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)

    private fun notifier(controller: PermissionController): Notifier = AndroidNotifier(
        context = context,
        permissions = controller,
        renderer = NotificationRenderer(context, "test.hostile.ACTION"),
        coalescer = coalescer,
    )

    private fun note(
        actions: List<NotificationAction> = emptyList(),
        extras: Map<String, String>? = null,
    ): LocalNotification = LocalNotification(
        title = "T", body = "B", channel = NotificationChannelSpec("c", "C"),
        progress = NotificationProgress.Determinate(50), actions = actions, contentExtras = extras,
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private val gateAsked: Boolean get() = android.os.Build.VERSION.SDK_INT >= 33

    @Test
    fun `a controller that throws a runtime exception is an answer where it is asked`() {
        val controller = Throwing { IllegalStateException("not ready") }
        val notifier: Notifier = notifier(controller)

        val result: NotificationResult = bounded { notifier.post("a", note()) }

        if (gateAsked) {
            assertEquals("not ready", assertIs<NotificationResult.Failed>(result).cause?.message)
        } else {
            assertEquals(NotificationResult.Posted, result)
        }
        assertEquals(0, coalescer.claimCountForTest())
        controller.armed = false
        notifier.cancel("a")
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `a controller that throws an Error escapes the post but leaves no claim and no held lock`() {
        if (!gateAsked) return
        val controller = Throwing { NotImplementedError("stub") }
        val notifier: Notifier = notifier(controller)

        assertFailsWith<NotImplementedError> { bounded { notifier.post("a", note()) } }

        assertEquals(0, coalescer.claimCountForTest())
        controller.armed = false
        notifier.cancel("a")
        notifier.cancelAll()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
        assertEquals(NotificationResult.Coalesced, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `a button list that throws when read is a Failed answer and the claim is released`() {
        val hostile: List<NotificationAction> = object : AbstractList<NotificationAction>() {
            override val size: Int get() = 1
            override fun get(index: Int): NotificationAction = throw ConcurrentModificationException("edited under us")
        }
        val controller = Throwing { error("unused") }.also { it.armed = false }
        val notifier: Notifier = notifier(controller)

        assertIs<NotificationResult.Failed>(bounded { notifier.post("a", note(actions = hostile)) })

        assertEquals(0, coalescer.claimCountForTest())
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }

    @Test
    fun `an extras map that throws when read is a Failed answer and the claim is released`() {
        val hostile: Map<String, String> = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = throw ConcurrentModificationException("edited under us")
        }
        val controller = Throwing { error("unused") }.also { it.armed = false }
        val notifier: Notifier = notifier(controller)

        assertIs<NotificationResult.Failed>(bounded { notifier.post("a", note(extras = hostile)) })

        assertEquals(0, coalescer.claimCountForTest())
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note()) })
    }
}
