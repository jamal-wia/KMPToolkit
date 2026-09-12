package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.SpecialPermission
import io.github.jamal_wia.kmptoolkit.permission.SpecialPermissionHandler

/**
 * A [SpecialPermissionHandler] double that records what it was asked and answers whatever the test
 * has scripted.
 *
 * The real handlers cannot be driven from a unit test — one reads real device state through a dozen
 * platform APIs, the other is a hardcoded "always granted" — so this is what a screen reacting to a
 * special-access permission tests against.
 *
 * ```kotlin
 * val handler = RecordingSpecialPermissionHandler()
 * handler.setGranted(SpecialPermission.EXACT_ALARM, granted = false)
 *
 * assertFalse(handler.isGranted(SpecialPermission.EXACT_ALARM))
 * handler.requestViaSettings(SpecialPermission.EXACT_ALARM)
 * assertEquals(listOf(SpecialPermission.EXACT_ALARM), handler.requestedViaSettings)
 * ```
 *
 * **Not thread-safe**, deliberately — see [RecordingPermissionHandler]'s KDoc for why.
 *
 * @param defaultGranted what [isGranted] answers for a permission no test has scripted with
 *   [setGranted]. Defaults to `true`, matching what both real handlers report for the common case
 *   (iOS always, and most special accesses being off is the exceptional path a test opts into).
 */
public class RecordingSpecialPermissionHandler(
    public var defaultGranted: Boolean = true,
) : SpecialPermissionHandler {

    private val granted: MutableMap<SpecialPermission, Boolean> = mutableMapOf()
    private val recordedChecks: MutableList<SpecialPermission> = mutableListOf()
    private val recordedRequests: MutableList<SpecialPermission> = mutableListOf()

    /** Every permission passed to [isGranted] so far, oldest first. A snapshot. */
    public val checks: List<SpecialPermission> get() = recordedChecks.toList()

    /** Every permission passed to [requestViaSettings] so far, oldest first. A snapshot. */
    public val requestedViaSettings: List<SpecialPermission> get() = recordedRequests.toList()

    /**
     * What [requestViaSettings] reports for a permission no test has scripted with
     * [setSettingsAvailable]. Defaults to `true` — the common case is that the screen opened.
     */
    public var defaultSettingsAvailable: Boolean = true

    private val settingsAvailable: MutableMap<SpecialPermission, Boolean> = mutableMapOf()

    /** Sets what [isGranted] reports for [permission] from now on. */
    public fun setGranted(permission: SpecialPermission, granted: Boolean) {
        this.granted[permission] = granted
    }

    /**
     * Sets what [requestViaSettings] reports for [permission] — set to `false` to exercise the rare
     * device where the Settings screen cannot be opened at all.
     */
    public fun setSettingsAvailable(permission: SpecialPermission, available: Boolean) {
        settingsAvailable[permission] = available
    }

    /** Drops both recordings, leaving every scripted answer in place. */
    public fun clearRecordings() {
        recordedChecks.clear()
        recordedRequests.clear()
    }

    override fun isGranted(permission: SpecialPermission): Boolean {
        recordedChecks += permission
        return granted[permission] ?: defaultGranted
    }

    override fun requestViaSettings(permission: SpecialPermission): Boolean {
        recordedRequests += permission
        return settingsAvailable[permission] ?: defaultSettingsAvailable
    }
}
