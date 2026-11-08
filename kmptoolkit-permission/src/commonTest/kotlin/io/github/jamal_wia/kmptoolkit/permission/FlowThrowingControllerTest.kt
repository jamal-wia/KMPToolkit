package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * `PermissionRequestFlow` documents that it never stays `Requesting` forever ("Left at Requesting, every
 * method would refuse to move the flow for the life of its holder"). A controller that throws from
 * `request` (a custom implementation of the public interface) must not strand it there either.
 */
class FlowThrowingControllerTest {

    private class ThrowingController(private val status: PermissionStatus) : PermissionController {
        var requests: Int = 0
        override suspend fun check(permission: Permission): PermissionStatus = status
        override suspend fun request(permission: Permission): PermissionStatus {
            requests++
            if (requests == 1) throw IllegalStateException("the platform failed")
            return PermissionStatus.Granted
        }
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> = emptyMap()
        override fun observe(permission: Permission): Flow<PermissionStatus> = emptyFlow()
        override fun openSettings(permission: Permission): Boolean = false
    }

    @Test
    fun `a request that throws does not leave the flow requesting for good`() = runTest {
        val flow = PermissionRequestFlow(CommonPermission.Camera, ThrowingController(PermissionStatus.NotDetermined))

        assertFailsWith<IllegalStateException> { flow.start() }

        assertNotEquals(PermissionFlowState.Requesting, flow.state.value, "the flow is stuck on Requesting after the failure")
        // and it is usable again
        assertEquals(PermissionFlowState.Granted, flow.start())
    }

    @Test
    fun `a rationale acknowledgement whose request throws does not leave the flow requesting for good`() = runTest {
        val flow = PermissionRequestFlow(CommonPermission.Camera, ThrowingController(PermissionStatus.Denied(explainFirst = true)))
        assertEquals(PermissionFlowState.AwaitingRationale, flow.start())

        assertFailsWith<IllegalStateException> { flow.rationaleAcknowledged() }

        assertNotEquals(PermissionFlowState.Requesting, flow.state.value, "the flow is stuck on Requesting after the failure")
    }
}
