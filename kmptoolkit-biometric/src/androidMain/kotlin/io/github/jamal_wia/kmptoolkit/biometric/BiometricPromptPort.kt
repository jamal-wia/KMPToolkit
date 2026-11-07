package io.github.jamal_wia.kmptoolkit.biometric

import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import java.util.concurrent.Executor

/**
 * The narrow slice of `androidx.biometric` this module drives.
 *
 * It exists so that the parts of the gate that are pure decision-making — no host activity, one
 * outcome per call, cancellation dismissing the sheet — can be exercised without an emulator, a
 * fingerprint sensor, or a user's finger, none of which a unit test has.
 */
internal fun interface BiometricPromptPort {

    /**
     * Shows the system prompt and reports the single terminal outcome through [onOutcome].
     *
     * @param requireConfirmation whether a passive biometric needs a confirming tap on this prompt.
     * @return a handle for dismissing the prompt, or `null` when there is no activity able to host
     *   it — in which case [onOutcome] is never called and nothing was shown.
     */
    fun show(
        prompt: BiometricPromptText,
        requireConfirmation: Boolean,
        onOutcome: (BiometricResult) -> Unit,
    ): PromptHandle?
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
 * the duration of the posted block. The handle returned to the caller holds no activity at all, only the
 * system prompt, and lets go of that as soon as an outcome has been delivered. The port also drops its
 * reference to the caller's outcome callback at that moment. What it cannot control is the framework:
 * androidx keeps the prompt and its callback for as long as it likes, so this module only guarantees
 * that it is not what keeps them reachable once the prompt is over.
 */
internal class ActivityBiometricPromptPort(
    private val activityAccess: ActivityAccess,
    private val config: BiometricGateConfig,
    private val options: BiometricGateOptions = BiometricGateOptions(),
    // Test seams: `BiometricPrompt` is final and Robolectric has no biometric service, so a test
    // needs to see the callback the port builds and to keep the real prompt from starting.
    private val promptFactory: (FragmentActivity, Executor, BiometricPrompt.AuthenticationCallback) -> BiometricPrompt =
        { activity, executor, callback -> BiometricPrompt(activity, executor, callback) },
    private val startPrompt: (BiometricPrompt, BiometricPrompt.PromptInfo) -> Unit =
        { systemPrompt, info -> systemPrompt.authenticate(info) },
) : BiometricPromptPort {

    override fun show(
        prompt: BiometricPromptText,
        requireConfirmation: Boolean,
        onOutcome: (BiometricResult) -> Unit,
    ): PromptHandle? {
        val activity: FragmentActivity =
            activityAccess.withActivity { it as? FragmentActivity } ?: return null
        val handle = ActivityPromptHandle()
        val deliverOnce: (BiometricResult) -> Unit = deliverOnceAndRelease(handle, onOutcome)
        activity.runOnUiThread {
            if (handle.isCancelled) return@runOnUiThread
            val systemPrompt: BiometricPrompt = promptFactory(
                activity,
                ContextCompat.getMainExecutor(activity),
                authenticationCallback(options.singleAttempt, deliverOnce, handle::cancel),
            )
            handle.attach(systemPrompt)
            runCatching {
                startPrompt(systemPrompt, buildPromptInfo(prompt, config, options.strength, requireConfirmation))
            }
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
}

/**
 * A callback that fires at most once. Android is well-behaved here, but a duplicate delivery would
 * resume an already-resumed continuation, which is a crash rather than a misreport — cheap to make
 * impossible. The first delivery also [releases][ActivityPromptHandle.release] [handle]: the outcome
 * is terminal (a [BiometricResult.Rejected] excepted: its own dismissal releases the handle), so nothing is left to cancel and the prompt (which holds the activity's fragment
 * manager) must not stay reachable through the framework's stored callback.
 */
internal fun deliverOnceAndRelease(
    handle: ActivityPromptHandle,
    onOutcome: (BiometricResult) -> Unit,
): (BiometricResult) -> Unit {
    // The first delivery clears the reference: the framework may keep this lambda for as long as it
    // keeps the callback, and the caller's callback captures the caller's continuation.
    var pending: ((BiometricResult) -> Unit)? = onOutcome
    return { outcome: BiometricResult ->
        val target: ((BiometricResult) -> Unit)? = pending
        if (target != null) {
            pending = null
            // A single-attempt rejection is followed by a dismissal through the handle, which still
            // needs the prompt and releases it itself.
            if (outcome != BiometricResult.Rejected) handle.release()
            target(outcome)
        }
    }
}

/**
 * The framework callback for one prompt: every terminal outcome to [onOutcome], and — only when
 * [singleAttempt] — a non-match as [BiometricResult.Rejected] followed by [dismiss].
 *
 * [onOutcome] must deliver at most once; the dismissal's own `ERROR_CANCELED` relies on that to be
 * dropped rather than reported as the user cancelling.
 */
internal fun authenticationCallback(
    singleAttempt: Boolean,
    onOutcome: (BiometricResult) -> Unit,
    dismiss: () -> Unit,
): BiometricPrompt.AuthenticationCallback = object : BiometricPrompt.AuthenticationCallback() {

    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult): Unit =
        onOutcome(BiometricResult.Authenticated)

    override fun onAuthenticationError(errorCode: Int, errString: CharSequence): Unit =
        onOutcome(mapAuthenticationError(errorCode))

    override fun onAuthenticationFailed() {
        // Without singleAttempt the sheet stays up and the platform retries: not terminal.
        if (!singleAttempt) return
        // With it, this non-match is the attempt. Delivered before the sheet is dismissed, so the
        // ERROR_CANCELED that dismissal produces arrives second and is dropped by the at-most-once
        // guard instead of being reported as the user cancelling.
        onOutcome(BiometricResult.Rejected)
        dismiss()
    }
}

/**
 * Handle over a prompt that may not exist yet.
 *
 * [cancel] can arrive before the posted show has run — a coroutine cancelled in the same tick it
 * started — so the flag is checked by the show block too. Cancelling is always posted to the main
 * thread (run inline only when already on it), never decided from the calling thread: the prompt is
 * attached by the show block on main, so a cancel from another thread that looked at the prompt
 * itself could see it missing, return, and leave the sheet that attaches a moment later on screen.
 * Posted, it runs after the show block (the main queue is FIFO and the show block was posted first).
 *
 * It holds no activity: the main looper is reached directly rather than through one. The prompt it
 * does hold (it is what [cancel] acts on) is dropped by [release], which the port calls once the
 * outcome is delivered.
 */
internal class ActivityPromptHandle : PromptHandle {

    @Volatile
    var isCancelled: Boolean = false
        private set

    @Volatile
    private var released: Boolean = false

    @Volatile
    private var prompt: BiometricPrompt? = null

    fun attach(prompt: BiometricPrompt) {
        if (!released) this.prompt = prompt
    }

    /** Drops the prompt: an outcome has been delivered, so there is nothing left to cancel. */
    fun release() {
        released = true
        prompt = null
    }

    /** Test seam: whether the system prompt is still referenced. */
    internal fun holdsPromptForTest(): Boolean = prompt != null

    override fun cancel() {
        isCancelled = true
        runOnMain {
            val target: BiometricPrompt? = prompt
            // Dismissing is the last thing the prompt is needed for.
            release()
            target?.cancelAuthentication()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        val main: Looper = Looper.getMainLooper()
        if (Looper.myLooper() == main) block() else Handler(main).post(block)
    }
}
