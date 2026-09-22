package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenKind
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Creates the Android [BiometricGate], backed by `androidx.biometric.BiometricPrompt`.
 *
 * Build it once — in `Application.onCreate` — and pass the resulting [BiometricGate] into shared code.
 * It learns which activity is resumed from the activity-resumed callback, so a gate created after your
 * activity resumed — a lazy DI singleton first injected by a screen — answers
 * [BiometricResult.NoPromptHost] until that activity pauses and resumes again.
 *
 * @param context any `Context`; its application context is retained to query `BiometricManager`
 *   for [BiometricGate.availability] and to track the currently resumed activity, which the
 *   prompt needs to attach itself to — Android's biometric prompt is a fragment, so it needs a
 *   resumed `FragmentActivity` (or `AppCompatActivity`). A plain `ComponentActivity`, the default
 *   base of a Compose activity, is not one: it is `FragmentActivity`'s superclass. Passing an
 *   `Activity` here is harmless — nothing keeps a reference to it. When no `FragmentActivity` is
 *   resumed, [BiometricGate.authenticate] returns [BiometricResult.NoPromptHost] rather than
 *   throwing.
 * @param config which credentials count and whether passive biometrics need a confirming tap; see
 *   [BiometricGateConfig].
 *
 * [BiometricGate.launchEnrollment] opens its screen with [SystemScreenLauncher.SeparateTask] — in a
 * task of its own; [createBiometricGateWithLauncher] takes a launcher of your own.
 *
 * The app does **not** need `android.permission.USE_BIOMETRIC`: this library declares no permission
 * of its own, on purpose, and `androidx.biometric` does not require the app to declare one either —
 * see `docs/kmptoolkit-biometric/05-platform-notes.md`.
 */
public fun createBiometricGate(
    context: Context,
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate = createBiometricGate(context, config, BiometricGateOptions())

/**
 * Creates the Android [BiometricGate] with [options] beyond the policy — the weak sensor tier, one
 * sensor attempt per call, and the enrolment-launch throttle. Otherwise identical to the two-argument
 * overload, including its `FragmentActivity` requirement.
 *
 * [BiometricGate.launchEnrollment] opens its screen with [SystemScreenLauncher.SeparateTask]: a task
 * of its own, never the app's and never a Settings task left in the background.
 * [createBiometricGateWithLauncher] takes a launcher of your own.
 *
 * @throws IllegalArgumentException if [options] asks for [BiometricStrength.WEAK] together with
 *   [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL], a combination Android cannot express.
 * @since 1.5.0
 */
public fun createBiometricGate(
    context: Context,
    config: BiometricGateConfig,
    options: BiometricGateOptions,
): BiometricGate = buildBiometricGate(context, SystemScreenLauncher.SeparateTask, config, options, activityAccess = null)

/**
 * Creates the Android [BiometricGate] with the prompt hosted through [activityAccess] — your app's
 * own activity tracker — instead of one the gate registers itself. Otherwise identical to the
 * three-argument overload.
 *
 * The prompt attaches to whichever activity [activityAccess] answers with, so its `isTracked`
 * predicate is honoured: an activity it does not track never hosts the prompt, and
 * [BiometricGate.authenticate] returns [BiometricResult.NoPromptHost] while no tracked
 * `FragmentActivity` is resumed. The gate does not own [activityAccess] and never releases it.
 *
 * [BiometricGate.launchEnrollment] still opens its screen with [SystemScreenLauncher.SeparateTask]:
 * the tracker hosts the prompt, it does not decide the enrolment task. Pass
 * `SystemScreenLauncher.callerTask(activityAccess)` to [createBiometricGateWithLauncher] for that.
 *
 * @param activityAccess the tracker that hosts the prompt; create it in `Application.onCreate`,
 *   before any activity resumes.
 * @throws IllegalArgumentException if [options] asks for [BiometricStrength.WEAK] together with
 *   [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL], a combination Android cannot express.
 * @since 1.7.0
 */
public fun createBiometricGate(
    context: Context,
    activityAccess: ActivityAccess,
    config: BiometricGateConfig = BiometricGateConfig(),
    options: BiometricGateOptions = BiometricGateOptions(),
): BiometricGate = buildBiometricGate(context, SystemScreenLauncher.SeparateTask, config, options, activityAccess)

/**
 * Creates the Android [BiometricGate] with [systemScreenLauncher] deciding how
 * [BiometricGate.launchEnrollment] opens the enrolment screen. Otherwise identical to the
 * three-argument [createBiometricGate], or — when [activityAccess] is given — to the overload taking
 * an `ActivityAccess`.
 *
 * Each [BiometricGate.launchEnrollment] call that is not throttled calls [systemScreenLauncher]
 * exactly once, with one `SystemScreenRequest` of kind [BiometricEnrollmentScreen] holding one or
 * more candidate screens, most specific first, without launch flags. A throttled call never reaches
 * it. `false`, or a launcher that throws, is reported as [BiometricEnrollmentLaunch.UNAVAILABLE].
 *
 * For an ordinary app, `SystemScreenLauncher.callerTask(activityAccess)` is the launcher to pass:
 * the screen opens on your own task, Back returns to your screen, and a two-pane Settings has
 * nothing to hand off. A lock-task (kiosk) app keeps [SystemScreenLauncher.SeparateTask] —
 * `callerTask` would put Settings inside the locked task — and wraps it in a launcher that opens its
 * lock-task allowlist window only for [BiometricEnrollmentScreen]; see
 * `docs/kmptoolkit-biometric/03-guide.md`.
 *
 * @param systemScreenLauncher how the enrolment screen is opened; called on the thread that called
 *   [BiometricGate.launchEnrollment].
 * @param activityAccess the tracker that hosts the prompt, its `isTracked` predicate honoured; `null`
 *   (the default) makes the gate register a tracker of its own, as the other factories without one do.
 * @throws IllegalArgumentException if [options] asks for [BiometricStrength.WEAK] together with
 *   [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL], a combination Android cannot express.
 * @since 1.7.0
 */
public fun createBiometricGateWithLauncher(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    config: BiometricGateConfig = BiometricGateConfig(),
    options: BiometricGateOptions = BiometricGateOptions(),
    activityAccess: ActivityAccess? = null,
): BiometricGate = buildBiometricGate(context, systemScreenLauncher, config, options, activityAccess)

/** Every factory ends here; `activityAccess == null` registers the gate's own tracker. */
private fun buildBiometricGate(
    context: Context,
    systemScreenLauncher: SystemScreenLauncher,
    config: BiometricGateConfig,
    options: BiometricGateOptions,
    activityAccess: ActivityAccess?,
): BiometricGate {
    require(options.strength == BiometricStrength.STRONG || config.policy == BiometricPolicy.BIOMETRIC_ONLY) {
        "BiometricStrength.WEAK is only valid with BiometricPolicy.BIOMETRIC_ONLY, was ${config.policy}"
    }
    val applicationContext: Context = context.applicationContext
    val manager: BiometricManager = BiometricManager.from(applicationContext)
    val promptHost: ActivityAccess = activityAccess ?: createActivityAccess(applicationContext as Application)
    return AndroidBiometricGate(
        status = BiometricStatusPort { allowed -> manager.canAuthenticate(allowed) },
        prompt = ActivityBiometricPromptPort(promptHost, config, options),
        config = config,
        options = options,
        enrollment = systemScreenLauncher.enrollmentStarter(applicationContext),
    )
}

/**
 * The screen [BiometricGate.launchEnrollment] opens — the enrolment wizard, the biometrics management
 * screen, or the security settings below API 30 — as the `kind` of the `SystemScreenRequest` a
 * [SystemScreenLauncher] receives from this module. Match it to treat enrolment differently from
 * other system screens, for example to open a kiosk's lock-task allowlist window around it.
 *
 * @since 1.7.0
 */
public object BiometricEnrollmentScreen : SystemScreenKind {
    override fun toString(): String = "BiometricEnrollmentScreen"
}

/** The `BiometricManager.canAuthenticate` query, isolated so tests can answer it directly. */
internal fun interface BiometricStatusPort {

    /** Returns a raw `BiometricManager.BIOMETRIC_*` status for the given authenticator mask. */
    fun canAuthenticate(allowedAuthenticators: Int): Int
}

/**
 * Orchestration only: everything platform-facing lives behind [BiometricStatusPort] and
 * [BiometricPromptPort], and every translation lives in `BiometricErrorMapping.kt`.
 *
 * The one piece of real logic here is the bridge between a callback API that fires once and a
 * cancellable suspending function. `suspendCancellableCoroutine` resumes on the first outcome and
 * dismisses the sheet if the caller's coroutine is cancelled first — in which case no
 * [BiometricResult] is produced at all, because a cancelled caller is not waiting for one.
 */
internal class AndroidBiometricGate(
    private val status: BiometricStatusPort,
    private val prompt: BiometricPromptPort,
    private val config: BiometricGateConfig,
    private val options: BiometricGateOptions = BiometricGateOptions(),
    enrollment: EnrollmentScreenStarter = EnrollmentScreenStarter { false },
    sdkInt: Int = Build.VERSION.SDK_INT,
    elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : BiometricGate {

    private val enrollmentLauncher = BiometricEnrollmentLauncher(
        sdkInt = sdkInt,
        weakTier = options.strength == BiometricStrength.WEAK,
        throttleMillis = options.enrollmentThrottle.inWholeMilliseconds,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        starter = enrollment,
    )

    override suspend fun availability(): BiometricAvailability =
        mapCanAuthenticate(status.canAuthenticate(config.allowedAuthenticators(options.strength)))

    override suspend fun authenticate(prompt: BiometricPromptText): BiometricResult =
        authenticate(prompt, config.requireExplicitConfirmation)

    override suspend fun launchEnrollment(): BiometricEnrollmentLaunch =
        // Only "nothing enrolled" goes to the enrolment wizard: it closes itself at once for any other state,
        // a lockout or a pending security update included, where the management screen is the useful one.
        enrollmentLauncher.launch(
            enrolled = availability() != BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
        )

    override suspend fun authenticate(
        prompt: BiometricPromptText,
        requireExplicitConfirmation: Boolean,
    ): BiometricResult =
        suspendCancellableCoroutine { continuation ->
            val handle: PromptHandle? = this.prompt.show(prompt, requireExplicitConfirmation) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
            if (handle == null) {
                continuation.resume(BiometricResult.NoPromptHost)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { handle.cancel() }
        }
}
