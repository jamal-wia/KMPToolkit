package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Application
import android.content.Context
import androidx.biometric.BiometricManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Creates the Android [BiometricGate], backed by `androidx.biometric.BiometricPrompt`.
 *
 * Build it once — in your `Application`, or wherever you assemble dependencies — and pass the
 * resulting [BiometricGate] into shared code.
 *
 * @param context any `Context`; its application context is retained to query `BiometricManager`
 *   for [BiometricGate.availability] and to track the currently resumed activity, which the
 *   prompt needs to attach itself to — Android's biometric prompt is a fragment, so it needs a
 *   resumed `FragmentActivity`. Passing an `Activity` here is harmless — nothing keeps a reference
 *   to it. When no such activity is resumed, [BiometricGate.authenticate] returns
 *   [BiometricResult.NoPromptHost] rather than throwing.
 * @param config which credentials count and whether passive biometrics need a confirming tap; see
 *   [BiometricGateConfig].
 *
 * The app does **not** need `android.permission.USE_BIOMETRIC`: this library declares no permission
 * of its own, on purpose, and `androidx.biometric` does not require the app to declare one either —
 * see `docs/kmptoolkit-biometric/05-platform-notes.md`.
 */
public fun createBiometricGate(
    context: Context,
    config: BiometricGateConfig = BiometricGateConfig(),
): BiometricGate {
    val applicationContext: Context = context.applicationContext
    val manager: BiometricManager = BiometricManager.from(applicationContext)
    val activityAccess: ActivityAccess = createActivityTracker(applicationContext as Application)
    return AndroidBiometricGate(
        status = BiometricStatusPort { allowed -> manager.canAuthenticate(allowed) },
        prompt = ActivityBiometricPromptPort(activityAccess, config),
        config = config,
    )
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
) : BiometricGate {

    override suspend fun availability(): BiometricAvailability =
        mapCanAuthenticate(status.canAuthenticate(config.allowedAuthenticators()))

    override suspend fun authenticate(prompt: BiometricPromptText): BiometricResult =
        suspendCancellableCoroutine { continuation ->
            val handle: PromptHandle? = this.prompt.show(prompt) { outcome ->
                if (continuation.isActive) continuation.resume(outcome)
            }
            if (handle == null) {
                continuation.resume(BiometricResult.NoPromptHost)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { handle.cancel() }
        }
}
