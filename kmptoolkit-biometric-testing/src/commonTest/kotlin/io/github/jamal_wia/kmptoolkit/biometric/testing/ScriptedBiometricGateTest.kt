package io.github.jamal_wia.kmptoolkit.biometric.testing

import io.github.jamal_wia.kmptoolkit.biometric.BiometricAvailability
import io.github.jamal_wia.kmptoolkit.biometric.BiometricGate
import io.github.jamal_wia.kmptoolkit.biometric.BiometricPromptText
import io.github.jamal_wia.kmptoolkit.biometric.BiometricResult
import io.github.jamal_wia.kmptoolkit.biometric.BiometricUnavailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The fixture's own contract — which is what consumers' assertions rest on, so a fixture that
 * quietly lies is worse than no fixture.
 *
 * The case worth stating: a scripted failure must still record the prompt. A test asserting "we
 * asked with the right words" has to work in the failure branch too, since that is the branch most
 * consumers are here to exercise.
 */
class ScriptedBiometricGateTest {

    private val promptText = BiometricPromptText(
        title = "Unlock",
        subtitle = "Confirm it is you",
        cancelLabel = "Cancel",
    )

    @Test
    fun `an unconfigured gate authenticates and is available`() = runTest {
        val gate: BiometricGate = ScriptedBiometricGate()

        assertEquals(BiometricAvailability.Available, gate.availability())
        assertEquals(BiometricResult.Authenticated, gate.authenticate(promptText))
    }

    @Test
    fun `availability reports whatever it was set to`() = runTest {
        BiometricUnavailability.entries.forEach { reason ->
            val gate = ScriptedBiometricGate(
                availability = BiometricAvailability.Unavailable(reason),
            )

            assertEquals(BiometricAvailability.Unavailable(reason), gate.availability())
        }
    }

    @Test
    fun `availability can change mid-test the way a real device does`() = runTest {
        val gate = ScriptedBiometricGate(
            availability = BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
        )

        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            gate.availability(),
        )

        // The user leaves for system settings and comes back with a finger enrolled.
        gate.availability = BiometricAvailability.Available

        assertEquals(BiometricAvailability.Available, gate.availability())
    }

    @Test
    fun `every failure branch is reachable`() = runTest {
        val outcomes: List<BiometricResult> = listOf(
            BiometricResult.Authenticated,
            BiometricResult.Cancelled,
            BiometricResult.Rejected,
            BiometricResult.NoPromptHost,
            BiometricResult.Failed(),
            BiometricResult.Failed(7),
        ) + BiometricUnavailability.entries.map { BiometricResult.Unavailable(it) }

        outcomes.forEach { outcome ->
            val gate = ScriptedBiometricGate(resultFor = { _, _ -> outcome })

            assertEquals(outcome, gate.authenticate(promptText), "outcome=$outcome")
        }
    }

    @Test
    fun `the script sees a 1-based attempt number so a retry flow can be walked`() = runTest {
        val gate = ScriptedBiometricGate(
            resultFor = { _, attempt ->
                when (attempt) {
                    1 -> BiometricResult.Rejected
                    2 -> BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT)
                    else -> BiometricResult.Authenticated
                }
            },
        )

        assertEquals(BiometricResult.Rejected, gate.authenticate(promptText))
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT),
            gate.authenticate(promptText),
        )
        assertEquals(BiometricResult.Authenticated, gate.authenticate(promptText))
    }

    @Test
    fun `the script sees the prompt it is deciding about`() = runTest {
        val other = promptText.copy(title = "Approve payment")
        val gate = ScriptedBiometricGate(
            resultFor = { prompt, _ ->
                if (prompt.title == "Approve payment") {
                    BiometricResult.Cancelled
                } else {
                    BiometricResult.Authenticated
                }
            },
        )

        assertEquals(BiometricResult.Authenticated, gate.authenticate(promptText))
        assertEquals(BiometricResult.Cancelled, gate.authenticate(other))
    }

    @Test
    fun `prompts are recorded in order`() = runTest {
        val second = promptText.copy(subtitle = "Again")
        val gate = ScriptedBiometricGate()

        gate.authenticate(promptText)
        gate.authenticate(second)

        assertEquals(listOf(promptText, second), gate.prompts)
    }

    @Test
    fun `a prompt is recorded even when the scripted outcome is a failure`() = runTest {
        val gate = ScriptedBiometricGate(
            resultFor = { _, _ ->
                BiometricResult.Unavailable(BiometricUnavailability.NO_HARDWARE)
            },
        )

        gate.authenticate(promptText)

        assertEquals(listOf(promptText), gate.prompts)
    }

    @Test
    fun `the recorded prompt list is a snapshot`() = runTest {
        val gate = ScriptedBiometricGate()
        gate.authenticate(promptText)

        val snapshot: List<BiometricPromptText> = gate.prompts
        gate.authenticate(promptText)

        assertEquals(1, snapshot.size, "an earlier snapshot must not grow")
        assertEquals(2, gate.prompts.size)
    }

    @Test
    fun `availability checks are counted`() = runTest {
        val gate = ScriptedBiometricGate()

        assertEquals(0, gate.availabilityChecks)
        repeat(3) { gate.availability() }

        assertEquals(3, gate.availabilityChecks)
    }

    @Test
    fun `authenticating does not count as an availability check`() = runTest {
        val gate = ScriptedBiometricGate()

        gate.authenticate(promptText)

        assertEquals(0, gate.availabilityChecks)
    }

    @Test
    fun `clear drops recordings and resets the attempt counter`() = runTest {
        var lastAttempt = 0
        val gate = ScriptedBiometricGate(
            resultFor = { _, attempt ->
                lastAttempt = attempt
                BiometricResult.Authenticated
            },
        )
        gate.authenticate(promptText)
        gate.availability()

        gate.clear()
        gate.authenticate(promptText)

        assertTrue(gate.prompts.size == 1)
        assertEquals(0, gate.availabilityChecks)
        assertEquals(1, lastAttempt, "the attempt number restarts with the recordings")
    }

    @Test
    fun `clear leaves the script alone`() = runTest {
        val gate = ScriptedBiometricGate(
            availability = BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE),
            resultFor = { _, _ -> BiometricResult.Cancelled },
        )

        gate.clear()

        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE),
            gate.availability(),
        )
        assertEquals(BiometricResult.Cancelled, gate.authenticate(promptText))
    }

    @Test
    fun `the fixture never authenticates by accident when scripted not to`() = runTest {
        // The failure mode that would make every consumer's negative test vacuous.
        val gate = ScriptedBiometricGate(resultFor = { _, _ -> BiometricResult.Cancelled })

        repeat(5) { assertEquals(BiometricResult.Cancelled, gate.authenticate(promptText)) }
    }
}
