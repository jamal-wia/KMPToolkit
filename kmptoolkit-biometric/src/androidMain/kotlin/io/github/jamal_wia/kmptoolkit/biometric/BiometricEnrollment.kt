package io.github.jamal_wia.kmptoolkit.biometric

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager

/** Starts one settings screen; `false` or an [ActivityNotFoundException] when the device has none. */
internal fun interface EnrollmentScreenStarter {
    fun start(intent: Intent): Boolean
}

/**
 * The action of the system's modality-agnostic biometrics management screen. Not a public `Settings`
 * constant, hence the literal; tried first and skipped where a device does not resolve it.
 */
internal const val ACTION_COMBINED_BIOMETRICS_SETTINGS: String = "android.settings.COMBINED_BIOMETRICS_SETTINGS"

/**
 * [BiometricGate.launchEnrollment] on Android: which screen, in which order, and how often.
 *
 * **Which screen.** `Settings.ACTION_BIOMETRIC_ENROLL` is an *enrol-if-missing* entry point: the
 * platform's enrolment activity checks `canAuthenticate` for the requested tier first and, when that
 * already succeeds, finishes at once without rendering anything. Sent from a "manage my biometrics"
 * button for a user who has one enrolled, it looks like a dead button. So an enrolled user is sent to
 * the combined biometrics management screen — which confirms the device credential and lists what is
 * enrolled, where "add" opens the same wizard — and the wizard is the fallback. That screen also stays
 * inside the Settings app, which matters under lock-task mode, where only allowlisted packages may be
 * launched and the documented-looking alternatives resolve to other packages on recent Android.
 * Below API 30 neither exists; the security settings screen is the closest there is.
 *
 * **In which order.** Candidates are tried in turn; the first the device resolves wins.
 *
 * **In a new task.** Always, even from an activity, so a settings screen can never become part of the
 * app's own task — under lock-task mode, a settings record left at the root of the app's task after a
 * crash makes the task impossible to lock again.
 *
 * **How often.** At most once per throttle window, stamped before resolution: a launch that opened
 * nothing still consumes the window, so a double tap cannot stack two settings tasks, and the next
 * deliberate tap after the window works again — no latch that only a resume would clear.
 */
internal class BiometricEnrollmentLauncher(
    private val sdkInt: Int,
    private val weakTier: Boolean,
    private val throttleMillis: Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val starter: EnrollmentScreenStarter,
) {

    /** Elapsed-realtime stamp of the last launch attempt; `null` before the first. */
    private var lastLaunchAtMillis: Long? = null

    /** @param enrolled whether the gate's tier already has an enrolment, which decides the screen. */
    fun launch(enrolled: Boolean): BiometricEnrollmentLaunch {
        val now: Long = elapsedRealtimeMillis()
        val last: Long? = lastLaunchAtMillis
        if (last != null && now - last < throttleMillis) return BiometricEnrollmentLaunch.THROTTLED
        lastLaunchAtMillis = now

        for (intent in candidates(enrolled)) {
            val started: Boolean = try {
                starter.start(intent)
            } catch (_: ActivityNotFoundException) {
                false
            }
            if (started) return BiometricEnrollmentLaunch.LAUNCHED
        }
        return BiometricEnrollmentLaunch.UNAVAILABLE
    }

    internal fun candidates(enrolled: Boolean): List<Intent> {
        if (sdkInt < Build.VERSION_CODES.R) return listOf(Intent(Settings.ACTION_SECURITY_SETTINGS))
        val wizard: Intent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            if (weakTier) BiometricManager.Authenticators.BIOMETRIC_WEAK else BiometricManager.Authenticators.BIOMETRIC_STRONG,
        )
        return if (enrolled) listOf(Intent(ACTION_COMBINED_BIOMETRICS_SETTINGS), wizard) else listOf(wizard)
    }
}
