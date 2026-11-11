package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Two of the three gates in front of the coalescer (permission, app-wide switch) flip while posters run. Whatever each racing frame is told, the coalescer must
 * afterwards believe exactly what the tray shows: a frame in another bucket than the tray's must post,
 * and a repeat of it must be coalesced. A gate that rejects a frame has to leave nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class NotificationGateFlapStormAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "flap", name = "Flap")

    private class FlapPermissions(base: PermissionController = FakePermissionController(defaultStatus = PermissionStatus.Granted)) :
        PermissionController by base {
        @Volatile
        var status: PermissionStatus = PermissionStatus.Granted

        override suspend fun check(permission: Permission): PermissionStatus = status
    }

    private fun frame(id: String, percent: Int): LocalNotification = LocalNotification(
        title = id,
        body = "b",
        channel = channel,
        progress = NotificationProgress.Determinate(percent),
    )

    private fun notifier(permissions: PermissionController): Notifier = createNotifier(
        context, permissions, NotificationConfig(progressBucketPercent = 10, minProgressInterval = Duration.ZERO),
    )

    private fun trayProgress(id: String): Int? =
        shadowOf(manager).getNotification(id, 1)?.extras?.getInt(Notification.EXTRA_PROGRESS)

    private fun runStorm(
        notifier: Notifier,
        ids: List<String>,
        chaos: (Random) -> Unit,
        results: MutableList<NotificationResult>,
        errors: MutableList<Throwable>,
    ) {
        val done = AtomicBoolean(false)
        val chaosThread: Thread = thread(name = "kmptoolkit-adversarial-chaos") {
            val random = Random(99)
            while (!done.get()) {
                try {
                    chaos(random)
                } catch (e: Throwable) {
                    errors += e
                    return@thread
                }
                Thread.sleep(0, 200_000)
            }
        }
        val posters: List<Thread> = (0 until POSTERS).map { index: Int ->
            thread(name = "kmptoolkit-adversarial-poster-$index") {
                val random = Random(index)
                try {
                    repeat(ROUNDS) {
                        val id: String = ids[random.nextInt(ids.size)]
                        val percent: Int = random.nextInt(0, 100)
                        results += runBlocking { withTimeout(JOIN_MS) { notifier.post(id, frame(id, percent)) } }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        posters.forEach { it.join(JOIN_MS) }
        done.set(true)
        chaosThread.join(JOIN_MS)
        assertTrue(posters.none { it.isAlive } && !chaosThread.isAlive, "a thread is still running")
    }

    @Test
    fun `gates flapping under posters never leave the coalescer believing a frame the tray does not show`() {
        val permissions = FlapPermissions()
        val notifier: Notifier = notifier(permissions)
        val ids: List<String> = listOf("a", "b", "c")
        val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
        val errors: MutableList<Throwable> = CopyOnWriteArrayList()

        runStorm(
            notifier, ids,
            chaos = { random: Random ->
                permissions.status = when (random.nextInt(4)) {
                    0 -> PermissionStatus.Denied()
                    1 -> PermissionStatus.PermanentlyDenied
                    else -> PermissionStatus.Granted
                }
                shadowOf(manager).setNotificationsEnabled(random.nextInt(3) != 0)
            },
            results, errors,
        )
        permissions.status = PermissionStatus.Granted
        shadowOf(manager).setNotificationsEnabled(true)

        assertEquals(emptyList(), errors.map { it.toString() })
        assertTrue(results.none { it is NotificationResult.Failed }, "a gate flap produced a Failed")
        assertTrue(results.any { it == NotificationResult.PermissionDenied }, "the storm never closed the permission gate")
        for (id: String in ids) {
            val shown: Int? = trayProgress(id)
            val other: Int = if (shown == null) 50 else (shown + 50) % 100
            assertEquals(NotificationResult.Posted, runBlocking { notifier.post(id, frame(id, other)) }, "$id: a bucket the tray does not show was suppressed (tray showed $shown)")
            assertEquals(other, trayProgress(id))
            assertEquals(NotificationResult.Coalesced, runBlocking { notifier.post(id, frame(id, other)) }, id)
        }
    }

    @Test
    fun `a permission check that throws an Error propagates it and leaves nothing in flight`() {
        var failures = 1
        val controller = object : PermissionController by FakePermissionController(defaultStatus = PermissionStatus.Granted) {
            override suspend fun check(permission: Permission): PermissionStatus {
                if (failures-- > 0) throw AssertionError("the controller blew up")
                return PermissionStatus.Granted
            }
        }
        val notifier: Notifier = notifier(controller)

        assertFailsWith<AssertionError> { runBlocking { notifier.post("a", frame("a", 40)) } }

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a", 40)) }, "the failed attempt left a baseline or a claim behind")
        notifier.cancel("a")
        assertEquals(null, trayProgress("a"))
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a", 40)) })
    }

    private companion object {
        const val POSTERS: Int = 6
        const val ROUNDS: Int = 400
        const val JOIN_MS: Long = 60_000L
    }
}
