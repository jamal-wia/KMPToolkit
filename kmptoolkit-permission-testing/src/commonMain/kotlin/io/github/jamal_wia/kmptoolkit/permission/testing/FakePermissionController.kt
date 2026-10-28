package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [PermissionController] double that records what it was asked and answers whatever the test says.
 *
 * The real controllers cannot be driven from a unit test — one needs an activity showing a system
 * dialog, the other needs a device — so every interesting path through your own screens
 * (permanently denied, denied once, limited, revoked while you were backgrounded) is unreachable
 * without a double. This is that double, and it models the OS closely enough that a test passing
 * against it says something true:
 *
 * - **Any permission.** It works with every [Permission] — common ones in shared tests, and the
 *   platform ones (`AndroidPermission.ExactAlarm`, `IosPermission.Tracking`) in platform tests.
 * - **A request changes the status.** [request] returns the outcome scripted by [scriptRequest]
 *   *and* stores it, so the [check] that follows agrees with it — exactly as the OS would after the
 *   user answered the system UI.
 * - **A request that the OS would not show is not shown here either.** When the current status is
 *   [PermissionStatus.Granted], [PermissionStatus.PermanentlyDenied], [PermissionStatus.Restricted]
 *   or [PermissionStatus.Unavailable], [request] returns it untouched and ignores the script, as both
 *   real controllers do. From [PermissionStatus.Limited] a scripted outcome applies — the platforms
 *   that show something there — and an unscripted request stays [PermissionStatus.Limited].
 * - **Requests are serialized**, as the real controllers' are: a request made while another is in
 *   flight waits for it. Set [holdRequests] to keep a request suspended — to test what your code does
 *   while the system UI is up, or when it is cancelled then — and clear it to let them finish.
 * - **[observe] follows every change.** A flow from [observe] emits the current status and then every
 *   different status [setStatus], [request] or a changed [defaultStatus] produces — the double's
 *   stand-in for the user changing a permission in settings while the app was away.
 * - **Recording is independent of the answer.** A call is recorded whatever it returns; the question a
 *   recording answers is "did my code ask?", not "did the user say yes?".
 *
 * ```kotlin
 * val controller = FakePermissionController()
 * controller.setStatus(CommonPermission.Microphone, PermissionStatus.Denied(explainFirst = true))
 *
 * val flow = PermissionRequestFlow(CommonPermission.Microphone, controller)
 *
 * assertEquals(PermissionFlowState.AwaitingRationale, flow.start())
 * assertEquals(listOf(CommonPermission.Microphone), controller.checks)
 * ```
 *
 * **Not thread-safe**, deliberately: the backing maps and lists are plain collections. Drive it from
 * one test dispatcher, as `runTest` does.
 *
 * @param defaultStatus what [check] answers for a permission no test has scripted. Defaults to
 *   [PermissionStatus.NotDetermined] — a fresh install, which is where most flows start.
 */
public class FakePermissionController(
    defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionController {

    /** What [check] answers for a permission no test has scripted. Changing it re-emits through [observe]. */
    public var defaultStatus: PermissionStatus = defaultStatus
        set(value) {
            field = value
            changed()
        }

    /** Bumped on every change a status can observe; [observe] re-reads on each bump. */
    private val revision: MutableStateFlow<Long> = MutableStateFlow(0L)

    private val held: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val requestMutex: Mutex = Mutex()

    private val statuses: MutableMap<Permission, PermissionStatus> = mutableMapOf()
    private val requestOutcomes: MutableMap<Permission, PermissionStatus> = mutableMapOf()
    private val recordedChecks: MutableList<Permission> = mutableListOf()
    private val recordedRequests: MutableList<Permission> = mutableListOf()
    private val recordedBatches: MutableList<List<Permission>> = mutableListOf()
    private val recordedSettings: MutableList<Permission> = mutableListOf()

    /** Every permission passed to [check] so far, oldest first. A snapshot. */
    public val checks: List<Permission> get() = recordedChecks.toList()

    /**
     * Every permission passed to the single-permission [request] so far, oldest first — including the
     * ones that returned without the system UI being shown. A snapshot.
     */
    public val requests: List<Permission> get() = recordedRequests.toList()

    /** Every collection passed to the batch [request] so far, each as a list in iteration order. A snapshot. */
    public val batchRequests: List<List<Permission>> get() = recordedBatches.toList()

    /** Every permission passed to [openSettings] so far, including calls that returned `false`. A snapshot. */
    public val openedSettings: List<Permission> get() = recordedSettings.toList()

    /**
     * What [openSettings] reports. Set it to `false` to exercise the rare device where no settings
     * screen can be opened — a path a real controller can produce and most UIs forget.
     */
    public var settingsAvailable: Boolean = true

    /**
     * While `true`, every request suspends — as if the system UI were on screen — after being recorded
     * and before its outcome is applied. Setting it back to `false` lets held requests finish, in order.
     */
    public var holdRequests: Boolean
        get() = held.value
        set(value) {
            held.value = value
        }

    /**
     * Sets the status [check] reports for [permission] from now on — also how you model a permission
     * changed in settings while your app was backgrounded.
     */
    public fun setStatus(permission: Permission, status: PermissionStatus) {
        statuses[permission] = status
        changed()
    }

    /**
     * Sets what every subsequent [request] for [permission] resolves to, standing in for the user's
     * answer to the system UI. Without a script, a request from [PermissionStatus.NotDetermined] or
     * [PermissionStatus.Denied] resolves to [PermissionStatus.Granted], because a test that does not
     * care about the answer is nearly always testing the happy path.
     */
    public fun scriptRequest(permission: Permission, outcome: PermissionStatus) {
        requestOutcomes[permission] = outcome
    }

    /** Drops every recording, leaving every scripted status and outcome in place. */
    public fun clearRecordings() {
        recordedChecks.clear()
        recordedRequests.clear()
        recordedBatches.clear()
        recordedSettings.clear()
    }

    override suspend fun check(permission: Permission): PermissionStatus {
        recordedChecks += permission
        return statusOf(permission)
    }

    override suspend fun request(permission: Permission): PermissionStatus {
        recordedRequests += permission
        return requestMutex.withLock { answer(permission) }
    }

    override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> {
        recordedBatches += permissions.toList()
        return requestMutex.withLock {
            permissions.distinct().associateWith { permission -> answer(permission) }
        }
    }

    /** Not recorded in [checks]: observing is not a call a screen makes on purpose, it is a subscription. */
    override fun observe(permission: Permission): Flow<PermissionStatus> =
        revision.map { statusOf(permission) }.distinctUntilChanged()

    override fun openSettings(permission: Permission): Boolean {
        recordedSettings += permission
        return settingsAvailable
    }

    private suspend fun answer(permission: Permission): PermissionStatus {
        val current: PermissionStatus = statusOf(permission)
        val showsUi: Boolean = current is PermissionStatus.NotDetermined || current is PermissionStatus.Denied ||
            (current is PermissionStatus.Limited && permission in requestOutcomes)
        if (!showsUi) return current
        held.first { holding -> !holding }
        val outcome: PermissionStatus = requestOutcomes[permission] ?: PermissionStatus.Granted
        statuses[permission] = outcome
        changed()
        return outcome
    }

    private fun statusOf(permission: Permission): PermissionStatus = statuses[permission] ?: defaultStatus

    private fun changed() {
        revision.value += 1
    }
}
