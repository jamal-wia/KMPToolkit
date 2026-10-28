package io.github.jamal_wia.kmptoolkit.permission

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scriptable [PermissionController] for the flow tests.
 *
 * Deliberately not `kmptoolkit-permission-testing`'s `FakePermissionController`, even though the two
 * are close relatives: depending on that artifact from this module's own tests would be a project
 * dependency cycle, and — more usefully — it would mean the state machine were tested through a
 * fixture whose own behavior is asserted elsewhere. This one does exactly what each test says and
 * nothing more.
 */
internal class ScriptedPermissionController(
    var status: PermissionStatus = PermissionStatus.NotDetermined,
    var requestOutcome: PermissionStatus = PermissionStatus.Granted,
) : PermissionController {

    var checkCount: Int = 0
        private set

    var requestCount: Int = 0
        private set

    /** Every permission [openSettings] was called for. */
    val openedSettings: MutableList<Permission> = mutableListOf()

    /** What [openSettings] reports. */
    var settingsAvailable: Boolean = true

    /** Runs inside [request], before it resolves — a seam for holding the "dialog" open. */
    var whileRequesting: (suspend () -> Unit)? = null

    override suspend fun check(permission: Permission): PermissionStatus {
        checkCount++
        return status
    }

    override suspend fun request(permission: Permission): PermissionStatus {
        requestCount++
        whileRequesting?.invoke()
        status = requestOutcome
        return requestOutcome
    }

    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
        permissions.associateWith { permission -> request(permission) }

    override fun observe(permission: Permission): Flow<PermissionStatus> = flow { emit(check(permission)) }

    override fun openSettings(permission: Permission): Boolean {
        openedSettings += permission
        return settingsAvailable
    }
}
