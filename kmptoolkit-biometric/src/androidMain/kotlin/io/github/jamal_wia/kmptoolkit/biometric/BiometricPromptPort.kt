package io.github.jamal_wia.kmptoolkit.biometric

import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet

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
 * system prompt and the caller's outcome callback, and lets go of both as soon as an outcome has been
 * delivered or the handle is cancelled. What it cannot control is the framework: androidx keeps the
 * prompt and its callback for as long as it likes, so this module only guarantees that it is not what
 * keeps them reachable once the prompt is over.
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
    private val stopPrompt: (BiometricPrompt) -> Unit = { systemPrompt -> systemPrompt.cancelAuthentication() },
) : BiometricPromptPort {

    override fun show(
        prompt: BiometricPromptText,
        requireConfirmation: Boolean,
        onOutcome: (BiometricResult) -> Unit,
    ): PromptHandle? {
        val activity: FragmentActivity =
            activityAccess.withActivity { it as? FragmentActivity } ?: return null
        val handle = ActivityPromptHandle(onOutcome, stopPrompt)
        activity.runOnUiThread {
            // Cheap pre-check: a show that was cancelled before this block ran builds no prompt at all.
            if (!handle.isWaiting) return@runOnUiThread
            val systemPrompt: BiometricPrompt = promptFactory(
                activity,
                ContextCompat.getMainExecutor(activity),
                authenticationCallback(options.singleAttempt, handle::deliver, handle::cancel),
            )
            // The authoritative check: a cancel that landed while the prompt was being built wins,
            // and then the sheet must never be started.
            if (!handle.attach(systemPrompt)) return@runOnUiThread
            runCatching {
                startPrompt(systemPrompt, buildPromptInfo(prompt, config, options.strength, requireConfirmation))
            }
                .onFailure {
                    // The builder validates the authenticator/negative-button combination against
                    // the running API level, and throws rather than degrading. Reaching this is a
                    // bug in this module, not a device condition — but a crash inside a UI post is
                    // the least useful place to learn about it, so it becomes a Failed with no
                    // platform code (there is none: nothing platform-level was rejected).
                    handle.deliver(BiometricResult.Failed())
                }
        }
        return handle
    }
}

/**
 * The framework callback for one prompt: every terminal outcome to [onOutcome], and — only when
 * [singleAttempt] — a non-match as [BiometricResult.Rejected] followed by [dismiss].
 *
 * [onOutcome] must deliver at most once ([ActivityPromptHandle.deliver] does); the dismissal's own
 * `ERROR_CANCELED` relies on that to be dropped rather than reported as the user cancelling.
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
 * Handle over a prompt that may not exist yet, and the single owner of its lifecycle.
 *
 * The lifecycle lives in one [Phase] value, so every transition is one atomic update. [cancel] comes
 * from whichever thread cancels the caller and everything else from the main thread; because the
 * decision is made on the state rather than on the thread, a cancel that races the show block either
 * stops the prompt from being started ([attach] answers `false`) or finds it attached and dismisses
 * it. Delivery is at most once by the same transition, and the caller's callback and the system prompt
 * are dropped by it — the framework may keep our `AuthenticationCallback` (and so this handle) for as
 * long as it likes, and the handle then holds neither.
 *
 * It holds no activity: the main looper is reached directly rather than through one.
 */
internal class ActivityPromptHandle(
    onOutcome: (BiometricResult) -> Unit,
    private val stopPrompt: (BiometricPrompt) -> Unit = { prompt -> prompt.cancelAuthentication() },
) : PromptHandle {

    /**
     * Plain classes on purpose: `MutableStateFlow` compares with `equals`, and identity is what the
     * transitions below mean.
     */
    private sealed interface Phase {
        /** The posted show has not attached a prompt yet. */
        class Waiting(val sink: (BiometricResult) -> Unit) : Phase

        /** On screen, no outcome yet. */
        class Showing(val prompt: BiometricPrompt, val sink: (BiometricResult) -> Unit) : Phase

        /** A single-attempt rejection was delivered; the prompt is kept only for the dismissal. */
        class Dismissing(val prompt: BiometricPrompt) : Phase

        /** Nothing is held: an outcome was delivered, or the handle was cancelled. */
        data object Over : Phase
    }

    // D-21: a StateFlow used for its atomic update / getAndUpdate, which neither suspend nor block.
    // That is what is needed: `cancel` runs inside `invokeOnCancellation` and the framework callbacks
    // run on main, so neither may wait, and neither is a suspend context. Nobody collects it.
    private val phase: MutableStateFlow<Phase> = MutableStateFlow(Phase.Waiting(onOutcome))

    /** Whether no prompt has been attached and nothing has ended the handle yet. */
    val isWaiting: Boolean get() = phase.value is Phase.Waiting

    /**
     * Claims the handle for [prompt]. `false` means the handle ended first (a cancel, or an outcome):
     * the caller must not start the prompt.
     */
    fun attach(prompt: BiometricPrompt): Boolean {
        val after: Phase = phase.updateAndGet { current ->
            if (current is Phase.Waiting) Phase.Showing(prompt, current.sink) else current
        }
        return after is Phase.Showing && after.prompt === prompt
    }

    /**
     * At most once: the first outcome wins and later ones (the dismissal's `ERROR_CANCELED`, or any
     * outcome after [cancel]) are dropped. A single-attempt [BiometricResult.Rejected] keeps the
     * prompt for the dismissal that follows it; every other outcome ends the handle.
     */
    fun deliver(outcome: BiometricResult) {
        val before: Phase = phase.getAndUpdate { current ->
            when (current) {
                is Phase.Showing ->
                    if (outcome == BiometricResult.Rejected) Phase.Dismissing(current.prompt) else Phase.Over
                is Phase.Waiting -> Phase.Over
                else -> current
            }
        }
        val sink: (BiometricResult) -> Unit = when (before) {
            is Phase.Showing -> before.sink
            is Phase.Waiting -> before.sink
            else -> return
        }
        sink(outcome)
    }

    /** Idempotent, from any thread; dismissing the sheet is a UI call and is posted to main. */
    override fun cancel() {
        val before: Phase = phase.getAndUpdate { Phase.Over }
        val target: BiometricPrompt = when (before) {
            is Phase.Showing -> before.prompt
            is Phase.Dismissing -> before.prompt
            // Waiting: attach now refuses. Over: already ended.
            else -> return
        }
        runOnMain { stopPrompt(target) }
    }

    /** Test seam: whether the system prompt is still referenced. */
    internal fun holdsPromptForTest(): Boolean = phase.value.let { it is Phase.Showing || it is Phase.Dismissing }

    /** Test seam: whether the caller's outcome callback is still referenced. */
    internal fun holdsSinkForTest(): Boolean = phase.value.let { it is Phase.Showing || it is Phase.Waiting }

    private fun runOnMain(block: () -> Unit) {
        val main: Looper = Looper.getMainLooper()
        if (Looper.myLooper() == main) block() else Handler(main).post(block)
    }
}
