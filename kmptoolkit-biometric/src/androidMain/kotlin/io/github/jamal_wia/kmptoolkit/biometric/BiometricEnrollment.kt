package io.github.jamal_wia.kmptoolkit.biometric

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest

/**
 * Hands every candidate of one [BiometricGate.launchEnrollment] call to whatever opens it; `true` if a
 * screen was opened. May throw — [BiometricEnrollmentLauncher] treats a throw as `false`.
 */
internal fun interface EnrollmentScreenStarter {
    fun start(candidates: List<Intent>): Boolean
}

/**
 * The production [EnrollmentScreenStarter]: one [SystemScreenRequest] of kind [BiometricEnrollmentScreen]
 * per call, carrying every candidate, handed to this launcher once.
 */
internal fun SystemScreenLauncher.enrollmentStarter(context: Context): EnrollmentScreenStarter =
    EnrollmentScreenStarter { candidates ->
        launch(SystemScreenRequest(candidates, context, BiometricEnrollmentScreen))
    }

/**
 * The action of the system's modality-agnostic biometrics management screen. Not a public `Settings`
 * constant, hence the literal; tried first and skipped where a device does not resolve it.
 */
internal const val ACTION_COMBINED_BIOMETRICS_SETTINGS: String = "android.settings.COMBINED_BIOMETRICS_SETTINGS"

/**
 * [BiometricGate.launchEnrollment] on Android: which screen, in which order, and how often. *How* the
 * screen is started — which task it lands in — is the [SystemScreenLauncher]'s decision, not this
 * class's.
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
 * **In which order.** Every candidate goes to the launcher in one [SystemScreenRequest], in order;
 * the first the device resolves wins (`SystemScreenRequest.startFirstResolvable`). The intents carry
 * no launch flags.
 *
 * **In a new task.** Always, by default: the factories that take no launcher use
 * [SystemScreenLauncher.SeparateTask] — `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT` from the
 * application context — so a settings screen can never become part of the app's own task (under
 * lock-task mode, a settings record left at the root of the app's task after a crash makes the task
 * impossible to lock again), and never joins a Settings task left in the background either. Up to
 * 1.6.0 this was bare `FLAG_ACTIVITY_NEW_TASK`, which reuses any background task with Settings'
 * affinity — one opened from a deep link, say — so Back or the end of the wizard landed on that stale
 * page instead of the app. One residual the flags cannot change: on a two-pane Settings (large
 * screens, AOSP 12L+) the management screen — a `SettingsActivity` — started in a new task hands
 * itself to the Settings homepage, whose task may be a stale one. The enrolment wizard is not a
 * `SettingsActivity` and is not handed off; below API 30 there is no two-pane Settings. An app that
 * passes its own launcher decides all of this itself.
 *
 * **How often.** At most once per throttle window, stamped before the launcher is called: a launch
 * that opened nothing still consumes the window, so a double tap cannot stack two settings tasks, and
 * the next deliberate tap after the window works again — no latch that only a resume would clear. A
 * throttled call never reaches the launcher.
 *
 * **Not opened.** A launcher that returns `false` or throws — any `Exception` — maps to
 * [BiometricEnrollmentLaunch.UNAVAILABLE], the answer for "no screen was started".
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

        val started: Boolean = try {
            starter.start(candidates(enrolled))
        } catch (_: Exception) {
            // The app's own launcher failed; nothing was opened, which is an answer, not a crash.
            false
        }
        return if (started) BiometricEnrollmentLaunch.LAUNCHED else BiometricEnrollmentLaunch.UNAVAILABLE
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
