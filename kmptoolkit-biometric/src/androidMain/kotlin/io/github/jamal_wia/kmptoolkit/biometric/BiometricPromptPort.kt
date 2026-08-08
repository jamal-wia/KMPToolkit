package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivityAccess

/**
 * The narrow slice of `androidx.biometric` this module drives.
 *
 * It exists so that the parts of the gate that are pure decision-making — no host activity, one
 * outcome per call, cancellation dismissing the sheet — can be exercised without an emulator, a
 * fingerprint sensor, or a user's finger, none of which a unit test has.
 */
internal interface BiometricPromptPort {

    /**
     * Shows the system prompt and reports the single terminal outcome through [onOutcome].
     *
     * @return a handle for dismissing the prompt, or `null` when there is no activity able to host
     *   it — in which case [onOutcome] is never called and nothing was shown.
     */
    fun show(prompt: BiometricPromptText, onOutcome: (BiometricResult) -> Unit): PromptHandle?
}

/** A prompt that is (or is about to be) on screen. */
internal interface PromptHandle {

    /**
     * Dismisses the prompt without an outcome. Idempotent, and safe to call before the prompt has
     * actually appeared — the pending show is dropped instead.
     */
    fun cancel()
}

/**
 * The real port: an `androidx.biometric.BiometricPrompt` hosted by whichever activity is resumed.
 *
 * Two Android facts shape it. The prompt is a fragment, so it needs a live [FragmentActivity] and
 * cannot be shown from the background — that is what the `null` return means, and it is checked
 * before anything is posted so the caller learns immediately. And every `BiometricPrompt` call must
 * happen on the main thread, so the work is posted there while [show] itself stays callable from
 * any thread; [PromptHandle.cancel] is posted the same way and drops a show that has not run yet.
 *
 * The activity is reached through [ActivityAccess] and never stored: the reference lives only for
 * the duration of the posted block, so a prompt outliving its activity cannot leak one.
 */
internal class ActivityBiometricPromptPort(
    private val activityAccess: ActivityAccess,
    private val config: BiometricGateConfig,
) : BiometricPromptPort {

    override fun show(
        prompt: BiometricPromptText,
        onOutcome: (BiometricResult) -> Unit,
    ): PromptHandle? {
        val activity: FragmentActivity =
            activityAccess.withActivity { it as? FragmentActivity } ?: return null
        val handle = ActivityPromptHandle(activity)
        // A callback that fires at most once. Android is well-behaved here, but a duplicate
        // delivery would resume an already-resumed continuation, which is a crash rather than a
        // misreport — cheap to make impossible.
        var delivered = false
        val deliverOnce: (BiometricResult) -> Unit = { outcome ->
            if (!delivered) {
                delivered = true
                onOutcome(outcome)
            }
        }
        activity.runOnUiThread {
            if (handle.isCancelled) return@runOnUiThread
            val systemPrompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                callback(deliverOnce),
            )
            handle.attach(systemPrompt)
            runCatching { systemPrompt.authenticate(buildPromptInfo(prompt, config)) }
                .onFailure {
                    // The builder validates the authenticator/negative-button combination against
                    // the running API level, and throws rather than degrading. Reaching this is a
                    // bug in this module, not a device condition — but a crash inside a UI post is
                    // the least useful place to learn about it, so it becomes a Failed with no
                    // platform code (there is none: nothing platform-level was rejected).
                    deliverOnce(BiometricResult.Failed())
                }
        }
        return handle
    }

    private fun callback(onOutcome: (BiometricResult) -> Unit): BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult,
            ): Unit = onOutcome(BiometricResult.Authenticated)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence): Unit =
                onOutcome(mapAuthenticationError(errorCode))

            override fun onAuthenticationFailed(): Unit = Unit // Sheet stays up; not terminal.
        }
}

/**
 * Handle over a prompt that may not exist yet.
 *
 * [cancel] can arrive before the posted show has run — a coroutine cancelled in the same tick it
 * started — so the flag is checked by the show block too, and cancelling is otherwise posted to the
 * main thread because `cancelAuthentication` is a UI call like any other.
 */
private class ActivityPromptHandle(private val activity: FragmentActivity) : PromptHandle {

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    private var prompt: BiometricPrompt? = null

    fun attach(prompt: BiometricPrompt) {
        this.prompt = prompt
    }

    override fun cancel() {
        isCancelled = true
        activity.runOnUiThread { prompt?.cancelAuthentication() }
    }
}
