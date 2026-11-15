package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason

/*
 * The rules that turn what the smoke saw into a verdict, kept apart from Main.kt so the module's own tests can pin them: a
 * release gate whose verdict logic is wrong is worse than none.
 */

/** A step of the smoke failed; the message is the verdict's reason. */
internal class SmokeFailure(message: String) : RuntimeException(message)

/** What a failed key-store probe means for the secure half of the run. */
internal sealed interface ProbeFailureDecision {
    /** The secure round trip is skipped (printed as a skip, verdict PASS-WITH-SKIP): no key store can serve this session. */
    class Skip(val why: String) : ProbeFailureDecision

    /** The run fails with [reason]. */
    class Fail(val reason: String) : ProbeFailureDecision
}

/** The probe answers that mean "no key store can serve this session", not "the binding is broken". */
private val SKIPPABLE_REASONS: Set<UnsupportedReason> = setOf(UnsupportedReason.NO_KEY_STORE, UnsupportedReason.NO_USER_SESSION)

/**
 * Decides what the probe's [error] means. Only "no key store can serve this session" is a skip:
 * `Unsupported(NO_KEY_STORE | NO_USER_SESSION)`, and `Unavailable` WITHOUT a cause (the library's answer for a locked keychain or
 * collection while dialogs are forbidden). `Unavailable` WITH a cause is a failure: the library reports a native call that passed
 * its time limit as `Unavailable(IOException(...))`, and a hang in the binding is exactly the packaging defect this smoke exists to
 * catch, so it must never read as a locked keychain. A broken binding (`UNSUPPORTED_RUNTIME`, `NATIVE_ACCESS_UNAVAILABLE`) and every
 * other answer fail too, and [secureRequired] turns even a skip into a failure.
 */
internal fun decideProbeFailure(error: StorageError, secureRequired: Boolean): ProbeFailureDecision {
    val required: String = if (secureRequired) " (secure round trip required)" else ""
    return when {
        error is StorageError.Unavailable && error.cause != null ->
            ProbeFailureDecision.Fail("the key-store probe was unavailable with a cause, which is a failure and not a skip (a native call that hangs or errors is a packaging defect, a locked key store has no cause)$required: $error")
        !isNoKeyStore(error) -> ProbeFailureDecision.Fail("the key-store probe failed$required: $error")
        secureRequired -> ProbeFailureDecision.Fail("the key-store probe found no usable key store$required: $error")
        else -> ProbeFailureDecision.Skip(error.toString())
    }
}

private fun isNoKeyStore(error: StorageError): Boolean =
    error is StorageError.Unavailable || (error is StorageError.Unsupported && error.reason in SKIPPABLE_REASONS)

/**
 * Refuses a combination of options that cannot give an honest verdict: `--probe-only` never runs the secure round trip, so it
 * cannot satisfy `--secure-required`, and a green PASS-WITH-SKIP there would claim the opposite of what was asked.
 */
internal fun checkRunOptions(secureRequired: Boolean, probeOnly: Boolean) {
    if (secureRequired && probeOnly) {
        throw SmokeFailure("--secure-required and --probe-only contradict each other: a probe-only run never runs the secure round trip")
    }
}
