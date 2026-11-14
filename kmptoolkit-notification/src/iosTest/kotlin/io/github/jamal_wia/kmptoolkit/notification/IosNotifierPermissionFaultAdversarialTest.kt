package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * `docs/kmptoolkit-notification/04-api-reference.md`, `post`: "Every *runtime* failure is a
 * `NotificationResult`, never an exception. The single exception is a blank `id`." The Android
 * notifier turns a `RuntimeException` out of the permission controller into `Failed(cause)`; the
 * iOS notifier asks the same controller first thing and must answer the same way. Everything here
 * happens before the notification centre is reached (it cannot be reached from a unit test).
 */
class IosNotifierPermissionFaultAdversarialTest {

    private val notification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
    )

    private class ScriptedController(private val onCheck: suspend () -> PermissionStatus) : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = onCheck()
        override suspend fun request(permission: Permission): PermissionStatus = onCheck()
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { onCheck() }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Denied())
        override fun openSettings(permission: Permission): Boolean = false
    }

    @Test
    fun `a controller that throws is a Failed result carrying the cause and never an exception`() {
        val boom = IllegalStateException("controller exploded")
        val coalescer = ProgressCoalescer()
        val notifier = IosNotifier(ScriptedController { throw boom }, coalescer)

        val result: NotificationResult = runBlocking { withTimeout(TIMEOUT_MILLIS) { notifier.post("a", notification) } }

        val failed: NotificationResult.Failed = assertIs<NotificationResult.Failed>(result)
        assertSame(boom, failed.cause)
        assertEquals(0, coalescer.claimCountForTest())
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
