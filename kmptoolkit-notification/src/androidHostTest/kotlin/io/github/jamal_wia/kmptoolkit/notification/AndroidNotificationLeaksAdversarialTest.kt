package io.github.jamal_wia.kmptoolkit.notification

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Round 18 (leaks): the documented retention claims of the Android notifier, checked with weak
 * references after thousands of cycles. 04-api-reference: "Only the application context is retained on
 * Android, so passing an `Activity` leaks nothing"; a notifier holds no process-wide registration, so
 * it and the permission controller it was given are collectable once the app drops them; a post that is
 * cancelled while it waits for a permission answer leaves nothing behind that still points at its
 * notification.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AndroidNotificationLeaksAdversarialTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    /** A context that stands for an Activity: not the application context, and nothing but this test may keep it. */
    private class UiContext(base: Context) : ContextWrapper(base)

    private class GatedPermissions : PermissionController {
        val gate: CompletableDeferred<PermissionStatus> = CompletableDeferred()
        override suspend fun check(permission: Permission): PermissionStatus = gate.await()
        override suspend fun request(permission: Permission): PermissionStatus = gate.await()
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { gate.await() }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private class Granted : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private fun gone(reference: WeakReference<*>): Boolean {
        repeat(60) {
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            if (reference.get() == null) return true
            val ballast: Array<ByteArray> = Array(64) { ByteArray(256 * 1024) }
            assertNotNull(ballast)
            Thread.sleep(25)
        }
        return reference.get() == null
    }

    private fun channel(index: Int): NotificationChannelSpec =
        NotificationChannelSpec(id = "ch${index % 7}", name = "Channel ${index % 7}")

    private fun frame(index: Int, progress: NotificationProgress? = null): LocalNotification =
        LocalNotification(title = "t$index", body = "b$index", channel = channel(index), progress = progress)

    @Test
    fun `an activity style context given to the factory is not retained by thousands of posts and cancels`() = runBlocking {
        withTimeout(120_000) {
            // Everything that touches the context lives in this function, so no stack slot of the test body keeps it.
            fun exercise(): Pair<WeakReference<UiContext>, Notifier> {
                val ui = UiContext(app)
                val notifier: Notifier = createNotifier(ui, Granted(), NotificationConfig(minProgressInterval = Duration.ZERO))
                runBlocking {
                    repeat(3_000) { i: Int ->
                        notifier.post("id${i % 40}", frame(i, if (i % 3 == 0) NotificationProgress.Determinate(i % 100) else null))
                        if (i % 5 == 0) notifier.cancel("id${i % 40}")
                        if (i % 997 == 0) notifier.cancelAll()
                    }
                }
                // The foreground builder and the channel helpers take a context of their own.
                assertNotNull(buildForegroundNotification(ui, ForegroundNotificationId("fg", 77), frame(1)))
                NotificationChannels.ensure(ui, channel(1))
                NotificationChannels.delete(ui, "ch1")
                return WeakReference(ui) to notifier
            }
            val (reference, notifier) = exercise()
            assertTrue(gone(reference), "the activity style context is still reachable from the notifier or its helpers")
            // The notifier is still usable: the collection did not take anything it needs.
            assertEquals(NotificationResult.Posted, notifier.post("after", frame(2)))
        }
    }

    @Test
    fun `a real activity handed to the factory is not retained either`() = runBlocking {
        withTimeout(120_000) {
            fun open(): WeakReference<Activity> {
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                val reference = WeakReference(controller.get())
                controller.pause().stop().destroy()
                return reference
            }
            // Control: the harness itself must be able to release a destroyed activity, or the check below says nothing.
            val controlCollectable: Boolean = gone(open())

            fun exercise(): Pair<WeakReference<Activity>, Notifier> {
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                val notifier: Notifier =
                    createNotifier(controller.get(), Granted(), NotificationConfig(minProgressInterval = Duration.ZERO))
                runBlocking { repeat(500) { i: Int -> notifier.post("id${i % 10}", frame(i)) } }
                val reference = WeakReference(controller.get())
                controller.pause().stop().destroy()
                return reference to notifier
            }
            val (reference, notifier) = exercise()
            if (controlCollectable) {
                assertTrue(gone(reference), "an activity given to createNotifier stays reachable after it was destroyed")
            }
            assertEquals(NotificationResult.Posted, notifier.post("after", frame(3)))
        }
    }

    @Test
    fun `a notifier and its permission controller are collectable once dropped even with notifications showing`() = runBlocking {
        withTimeout(120_000) {
            fun use(): Pair<WeakReference<Notifier>, WeakReference<PermissionController>> {
                val permissions: PermissionController = Granted()
                val notifier: Notifier = createNotifier(app, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))
                runBlocking { repeat(2_000) { i: Int -> notifier.post("id${i % 25}", frame(i)) } }
                return WeakReference(notifier) to WeakReference(permissions)
            }
            val (notifier, permissions) = use()
            assertTrue(gone(notifier), "the notifier is held by something process wide")
            assertTrue(gone(permissions), "the permission controller is held by something process wide")
        }
    }

    @Test
    fun `a post cancelled while it waits for the permission leaves nothing pointing at its notification`() = runBlocking {
        withTimeout(120_000) {
            val gate = GatedPermissions()
            val notifier: Notifier = createNotifier(app, gate, NotificationConfig(minProgressInterval = Duration.ZERO))
            val references: MutableList<WeakReference<LocalNotification>> = mutableListOf()
            repeat(1_500) { i: Int ->
                val notification: LocalNotification = frame(i, NotificationProgress.Determinate(i % 100))
                references += WeakReference(notification)
                val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    notifier.post("id${i % 30}", notification)
                }
                if (i % 7 == 0) notifier.cancel("id${i % 30}")
                job.cancel()
                job.join()
            }
            val survivors: Int = run {
                repeat(60) { System.gc(); Thread.sleep(20) }
                references.count { it.get() != null }
            }
            // A handful of the newest may still be on a stack frame of this loop; thousands would be a leak.
            assertTrue(survivors <= 5, "$survivors of ${references.size} cancelled posts still have their notification reachable")
            gate.gate.complete(PermissionStatus.Granted)
            // And the notifier is not wedged by the cancelled posts: a fresh post goes through.
            assertEquals(NotificationResult.Posted, notifier.post("fresh", frame(9)))
        }
    }
}
