package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionHandler
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus

/**
 * A [PermissionHandler] double that records what it was asked and answers whatever the test says.
 *
 * The real handlers cannot be driven from a unit test — one needs an activity showing a system
 * dialog, the other needs a device — so every interesting path through your own screens
 * (permanently denied, denied once, revoked while you were backgrounded) is unreachable without a
 * double. This is that double, and it models the OS closely enough that a test passing against it
 * says something true:
 *
 * - **A request changes the status.** [request] returns the outcome scripted by
 *   [scriptRequest] *and* stores it, so the [check] that follows agrees with it — exactly as the OS
 *   would after the user tapped a button in the dialog.
 * - **A request that the OS would not show is not shown here either.** When the current status is
 *   [PermissionStatus.Granted] or [PermissionStatus.PermanentlyDenied], [request] returns that
 *   status untouched and ignores the script, because that is what both real handlers do.
 * - **Recording is independent of the answer.** A call is recorded whatever it returns; the
 *   question a recording answers is "did my code ask?", not "did the user say yes?".
 *
 * ```kotlin
 * val handler = RecordingPermissionHandler()
 * handler.setStatus(Permission.MICROPHONE, PermissionStatus.Denied(shouldShowRationale = true))
 *
 * val flow = PermissionRequestFlow(Permission.MICROPHONE, handler)
 *
 * assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
 * assertEquals(listOf(Permission.MICROPHONE), handler.checks)
 * ```
 *
 * **Not thread-safe**, deliberately: the backing maps and lists are plain collections, matching
 * both real handlers' own "drive it from one coroutine" contract. Making it concurrent would add a
 * dependency to an artifact whose value is being trivial.
 *
 * @param defaultStatus what [check] answers for a permission no test has scripted. Defaults to
 *   [PermissionStatus.NotDetermined] — a fresh install, which is where most flows start.
 */
public class RecordingPermissionHandler(
    public var defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionHandler {

    private val statuses: MutableMap<Permission, PermissionStatus> = mutableMapOf()
    private val requestOutcomes: MutableMap<Permission, PermissionStatus> = mutableMapOf()
    private val recordedChecks: MutableList<Permission> = mutableListOf()
    private val recordedRequests: MutableList<Permission> = mutableListOf()

    /**
     * Every permission passed to [check] so far, oldest first.
     *
     * A snapshot: it does not change when more calls arrive.
     */
    public val checks: List<Permission> get() = recordedChecks.toList()

    /**
     * Every permission passed to [request] so far, oldest first — including the ones that returned
     * without a dialog being shown.
     *
     * A snapshot, like [checks].
     */
    public val requests: List<Permission> get() = recordedRequests.toList()

    /** How many times [openAppSettings] was called, including calls that returned `false`. */
    public var openAppSettingsCount: Int = 0
        private set

    /**
     * What [openAppSettings] reports.
     *
     * Set it to `false` to exercise the rare device where the settings screen cannot be opened at
     * all — a path a real handler can produce and most UIs forget.
     */
    public var settingsAvailable: Boolean = true

    /**
     * Sets the status [check] will report for [permission] from now on.
     *
     * This is also how you model a permission revoked while your app was backgrounded: set it to
     * [PermissionStatus.NotDetermined] (or [PermissionStatus.PermanentlyDenied]) between the calls
     * where the user left and came back.
     */
    public fun setStatus(permission: Permission, status: PermissionStatus) {
        statuses[permission] = status
    }

    /**
     * Sets what the next — and every subsequent — [request] for [permission] resolves to, standing
     * in for the user's answer to the system dialog.
     *
     * Ignored while the current status already makes a dialog impossible; see the class KDoc.
     * Without a script, a request resolves to [PermissionStatus.Granted], because a test that does
     * not care about the answer is nearly always testing the happy path.
     */
    public fun scriptRequest(permission: Permission, outcome: PermissionStatus) {
        requestOutcomes[permission] = outcome
    }

    /** Drops both recordings, leaving every scripted status and outcome in place. */
    public fun clearRecordings() {
        recordedChecks.clear()
        recordedRequests.clear()
        openAppSettingsCount = 0
    }

    override suspend fun check(permission: Permission): PermissionStatus {
        recordedChecks += permission
        return statusOf(permission)
    }

    override suspend fun request(permission: Permission): PermissionStatus {
        recordedRequests += permission
        val current: PermissionStatus = statusOf(permission)
        if (current is PermissionStatus.Granted || current is PermissionStatus.PermanentlyDenied) {
            return current
        }
        val outcome: PermissionStatus = requestOutcomes[permission] ?: PermissionStatus.Granted
        statuses[permission] = outcome
        return outcome
    }

    override fun openAppSettings(): Boolean {
        openAppSettingsCount++
        return settingsAvailable
    }

    private fun statusOf(permission: Permission): PermissionStatus =
        statuses[permission] ?: defaultStatus
}
