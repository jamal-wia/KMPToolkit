package io.github.jamal_wia.kmptoolkit.permission

/**
 * A scriptable [PermissionHandler] for the flow tests.
 *
 * Deliberately not `kmptoolkit-permission-testing`'s `RecordingPermissionHandler`, even though the
 * two are close relatives: depending on that artifact from this module's own tests would be a
 * project dependency cycle, and — more usefully — it would mean the state machine were tested
 * through a fixture whose own behavior is asserted elsewhere. This one does exactly what each test
 * says and nothing more.
 */
internal class FakePermissionHandler(
    var status: PermissionStatus = PermissionStatus.NotDetermined,
    var requestOutcome: PermissionStatus = PermissionStatus.Granted,
) : PermissionHandler {

    var checkCount: Int = 0
        private set

    var requestCount: Int = 0
        private set

    var openAppSettingsCount: Int = 0
        private set

    /** What [openAppSettings] reports. */
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

    override fun openAppSettings(): Boolean {
        openAppSettingsCount++
        return settingsAvailable
    }
}
