package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The iOS column of the permission matrix (the Android column is
 * `NotificationPermissionStatusMatrixAdversarialTest`): every status that is not usable
 * (`Granted` and `Limited` are the usable ones) answers `PermissionDenied` before the notification
 * center is reached, the controller is asked for exactly `CommonPermission.Notifications` once per
 * post and never by `cancel` or `cancelAll`'s siblings, and a blank id never reaches the controller.
 * The usable statuses are not exercised here: past the gate the post needs a real center, which a
 * test binary does not have.
 */
class IosNotifierPermissionMatrixAdversarialTest {

    private val notification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
    )

    private class CountingController(private val status: PermissionStatus) : PermissionController {
        val checked: MutableList<Permission> = mutableListOf()
        var requested: Int = 0

        override suspend fun check(permission: Permission): PermissionStatus {
            checked += permission
            return status
        }

        override suspend fun request(permission: Permission): PermissionStatus {
            requested++
            return status
        }

        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> {
            requested++
            return permissions.associateWith { status }
        }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(status)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private val unusable: List<PermissionStatus> = listOf(
        PermissionStatus.Denied(),
        PermissionStatus.Denied(explainFirst = true),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
        PermissionStatus.Unavailable,
    )

    @Test
    fun `every status that is not usable answers PermissionDenied and the controller is asked once for notifications`() {
        for (status: PermissionStatus in unusable) {
            val controller = CountingController(status)
            val notifier: Notifier = createNotifier(controller)

            val result: NotificationResult = runBlocking { withTimeout(TIMEOUT_MILLIS) { notifier.post("a", notification) } }

            assertEquals(NotificationResult.PermissionDenied, result, "status $status")
            assertEquals(listOf<Permission>(CommonPermission.Notifications), controller.checked, "status $status")
            assertEquals(0, controller.requested, "the notifier must never prompt ($status)")
        }
    }

    @Test
    fun `three posts ask three times and the three-argument form behaves like the two-argument one`() {
        val controller = CountingController(PermissionStatus.Denied())
        val notifier: Notifier = createNotifier(controller)

        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                assertEquals(NotificationResult.PermissionDenied, notifier.post("a", notification))
                assertEquals(NotificationResult.PermissionDenied, notifier.post("a", notification, NotificationOptions.DEFAULT))
                assertEquals(
                    NotificationResult.PermissionDenied,
                    notifier.post("b", notification, NotificationOptions(alertOnce = false, mediaStyle = true)),
                )
            }
        }

        assertEquals(3, controller.checked.size)
    }

    @Test
    fun `a blank id is rejected by the three-argument form before the controller is asked`() {
        val controller = CountingController(PermissionStatus.Granted)
        val notifier: Notifier = createNotifier(controller)

        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                kotlin.test.assertFailsWith<IllegalArgumentException> {
                    notifier.post(" ", notification, NotificationOptions.DEFAULT)
                }
            }
        }

        assertEquals(emptyList(), controller.checked)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
