package io.github.jamal_wia.kmptoolkit.biometric.testing

import io.github.jamal_wia.kmptoolkit.biometric.BiometricAvailability
import io.github.jamal_wia.kmptoolkit.biometric.BiometricGate
import io.github.jamal_wia.kmptoolkit.biometric.BiometricPromptText
import io.github.jamal_wia.kmptoolkit.biometric.BiometricResult

/**
 * A [BiometricGate] that authenticates nobody, records every prompt, and returns whichever outcome
 * the test dictates.
 *
 * It exists because the branches worth testing in an app that uses this library are the ones a real
 * device will not give you on demand. An emulator has no lockout you can trigger, no
 * "permanently locked out" state you can enter and leave, and no way to be simultaneously enrolled
 * and unenrolled across two test cases. Here every branch is one assignment:
 *
 * ```kotlin
 * val gate = ScriptedBiometricGate(
 *     availability = BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
 * )
 * assertTrue(LockScreen(gate).load().showsEnrolmentShortcut)
 * ```
 *
 * [resultFor] is a lambda rather than a value so a single instance can walk a whole flow — reject
 * the first attempt, lock out the second, authenticate the third:
 *
 * ```kotlin
 * val gate = ScriptedBiometricGate()
 * gate.resultFor = { _, attempt ->
 *     when (attempt) {
 *         1 -> BiometricResult.Rejected
 *         2 -> BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT)
 *         else -> BiometricResult.Authenticated
 *     }
 * }
 * ```
 *
 * **Recording is independent of the outcome.** A prompt is recorded even when the scripted result
 * says the device could not show it, because the question the recording answers is "did my code ask
 * for this, with these words?", not "did a sheet appear?". That makes it the place to assert the
 * thing this library will not do for you: that your prompt copy is your own, localized, and not
 * empty.
 *
 * **Not thread-safe**, deliberately: the backing lists are plain `MutableList`s. Drive it from one
 * test coroutine and assert once the work under test has finished. Making it concurrent would mean
 * an atomics dependency in an artifact whose value is being trivial.
 *
 * @param availability what [availability] reports. Mutable, so one instance can go from unenrolled
 *   to enrolled the way a real device does when the user comes back from system settings.
 * @param resultFor what [authenticate] returns, given the prompt and the 1-based number of this
 *   attempt. Defaults to authenticating everything.
 */
public class ScriptedBiometricGate(
    public var availability: BiometricAvailability = BiometricAvailability.Available,
    public var resultFor: (prompt: BiometricPromptText, attempt: Int) -> BiometricResult =
        { _, _ -> BiometricResult.Authenticated },
) : BiometricGate {

    private val recordedPrompts: MutableList<BiometricPromptText> = mutableListOf()
    private var recordedAvailabilityChecks: Int = 0

    /**
     * Every prompt passed to [authenticate] so far, oldest first.
     *
     * A snapshot: the returned list does not change when more calls arrive, so holding on to it
     * across a later `authenticate` is safe.
     */
    public val prompts: List<BiometricPromptText> get() = recordedPrompts.toList()

    /**
     * How many times [availability] has been asked.
     *
     * Worth asserting when your screen is supposed to re-check on resume rather than cache the
     * answer from its first composition — a real device changes its mind while your app is in the
     * background.
     */
    public val availabilityChecks: Int get() = recordedAvailabilityChecks

    override suspend fun availability(): BiometricAvailability {
        recordedAvailabilityChecks++
        return availability
    }

    override suspend fun authenticate(prompt: BiometricPromptText): BiometricResult {
        recordedPrompts += prompt
        return resultFor(prompt, recordedPrompts.size)
    }

    /**
     * Drops every recording, including the attempt counter [resultFor] is given.
     *
     * [availability] and [resultFor] are left alone — clearing is for separating an arrange phase
     * that authenticated from the act phase you actually want to assert, not for un-scripting the
     * gate.
     */
    public fun clear() {
        recordedPrompts.clear()
        recordedAvailabilityChecks = 0
    }
}
