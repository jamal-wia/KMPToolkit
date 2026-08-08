package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The gate's own behaviour, with both platform seams replaced: what it asks `BiometricManager`,
 * what it does when no activity can host the prompt, that a single outcome comes back unmodified,
 * and that cancelling the caller dismisses the sheet without inventing a result.
 *
 * The prompt port is faked rather than driven through Robolectric because none of this is about
 * `androidx.biometric` — the framework-facing half is covered by `PromptInfoTest` and
 * `BiometricErrorMappingTest`.
 *
 * Every authentication is started `UNDISPATCHED` so the call runs up to its suspension point — past
 * `show`, before any outcome — inside the test's own control flow. That makes "the prompt is on
 * screen and nothing has happened yet" an ordinary, deterministic state to assert from, with no
 * polling and no virtual time.
 *
 * Robolectric at a pinned SDK, because the authenticator mask the gate asks about depends on
 * `Build.VERSION.SDK_INT` — which is 0 in a plain JVM unit test, i.e. neither of the two branches a
 * real device takes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidBiometricGateTest {

    private val promptText = BiometricPromptText(
        title = "Unlock",
        subtitle = "Confirm it is you",
        cancelLabel = "Cancel",
    )

    private class FakePromptPort(private val hasHost: Boolean = true) : BiometricPromptPort {

        val shown: MutableList<BiometricPromptText> = mutableListOf()

        var cancelCount: Int = 0
            private set

        private var pending: ((BiometricResult) -> Unit)? = null

        override fun show(
            prompt: BiometricPromptText,
            onOutcome: (BiometricResult) -> Unit,
        ): PromptHandle? {
            if (!hasHost) return null
            shown += prompt
            pending = onOutcome
            return object : PromptHandle {
                override fun cancel() {
                    cancelCount++
                }
            }
        }

        /** Delivers a terminal outcome the way the framework's callback would. */
        fun deliver(result: BiometricResult) {
            val callback: (BiometricResult) -> Unit =
                checkNotNull(pending) { "no prompt is on screen" }
            callback(result)
        }
    }

    private fun gate(
        port: BiometricPromptPort,
        config: BiometricGateConfig = BiometricGateConfig(),
        status: BiometricStatusPort = BiometricStatusPort { BiometricManager.BIOMETRIC_SUCCESS },
    ): BiometricGate = AndroidBiometricGate(status = status, prompt = port, config = config)

    // --- availability -------------------------------------------------------------------------

    @Test
    fun `availability asks about the strong biometric tier for a biometric-only gate`() = runTest {
        var asked: Int? = null

        gate(
            port = FakePromptPort(),
            status = BiometricStatusPort { allowed ->
                asked = allowed
                BiometricManager.BIOMETRIC_SUCCESS
            },
        ).availability()

        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, asked)
    }

    @Test
    fun `a credential-accepting gate asks about a wider authenticator set`() = runTest {
        var asked: Int? = null

        gate(
            port = FakePromptPort(),
            config = BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
            status = BiometricStatusPort { allowed ->
                asked = allowed
                BiometricManager.BIOMETRIC_SUCCESS
            },
        ).availability()

        // Robolectric's configured SDK is above 30, where the combination is expressible.
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            asked,
        )
    }

    @Test
    fun `availability reports whatever the platform status says`() = runTest {
        val cases: Map<Int, BiometricAvailability> = mapOf(
            BiometricManager.BIOMETRIC_SUCCESS to BiometricAvailability.Available,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE to
                BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE),
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED to
                BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE to
                BiometricAvailability.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE),
        )

        cases.forEach { (status, expected) ->
            val availability: BiometricAvailability =
                gate(port = FakePromptPort(), status = BiometricStatusPort { status })
                    .availability()

            assertEquals(expected, availability, "status=$status")
        }
    }

    @Test
    fun `availability is asked afresh every time rather than cached`() = runTest {
        var calls = 0
        val gate: BiometricGate = gate(
            port = FakePromptPort(),
            status = BiometricStatusPort {
                calls++
                if (calls == 1) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                } else {
                    BiometricManager.BIOMETRIC_SUCCESS
                }
            },
        )

        // A user who leaves to enrol a finger and comes back must not be told "not enrolled"
        // forever.
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            gate.availability(),
        )
        assertEquals(BiometricAvailability.Available, gate.availability())
    }

    // --- no host ------------------------------------------------------------------------------

    @Test
    fun `no resumed activity yields NoPromptHost and shows nothing`() = runTest {
        val port = FakePromptPort(hasHost = false)

        val result: BiometricResult = gate(port).authenticate(promptText)

        assertEquals(BiometricResult.NoPromptHost, result)
        assertTrue(port.shown.isEmpty(), "nothing may be shown when there is no host")
    }

    @Test
    fun `no resumed activity is not reported as a cancellation`() = runTest {
        // The user never saw a sheet; telling the app they cancelled would make it render a
        // "you declined" state for a prompt that never existed.
        val result: BiometricResult = gate(FakePromptPort(hasHost = false)).authenticate(promptText)

        assertFalse(result == BiometricResult.Cancelled)
    }

    // --- outcomes -----------------------------------------------------------------------------

    @Test
    fun `the prompt copy reaches the platform unchanged`() = runTest {
        val port = FakePromptPort()

        val running: Deferred<BiometricResult> = authenticating(port)
        port.deliver(BiometricResult.Authenticated)
        running.await()

        assertEquals(listOf(promptText), port.shown)
    }

    @Test
    fun `every outcome the platform produces is returned unmodified`() = runTest {
        val outcomes: List<BiometricResult> = listOf(
            BiometricResult.Authenticated,
            BiometricResult.Cancelled,
            BiometricResult.Rejected,
            BiometricResult.Failed(),
            BiometricResult.Failed(7),
        ) + BiometricUnavailability.entries.map { BiometricResult.Unavailable(it) }

        outcomes.forEach { outcome ->
            val port = FakePromptPort()
            val running: Deferred<BiometricResult> = authenticating(port)

            port.deliver(outcome)

            assertEquals(outcome, running.await(), "outcome=$outcome")
        }
    }

    @Test
    fun `a second outcome after the first is ignored rather than crashing`() = runTest {
        // Resuming an already-resumed continuation throws. The port guards against a duplicate
        // framework callback; the gate must not depend on that being the only guard.
        val port = FakePromptPort()
        val running: Deferred<BiometricResult> = authenticating(port)

        port.deliver(BiometricResult.Authenticated)
        port.deliver(BiometricResult.Cancelled)

        assertEquals(BiometricResult.Authenticated, running.await())
    }

    @Test
    fun `a rejected credential does not end the gate for the next attempt`() = runTest {
        // Rejected is terminal for one prompt, not for the instance: a wet finger must not make
        // the gate useless until the app restarts.
        val port = FakePromptPort()
        val gate: BiometricGate = gate(port)

        val first: Deferred<BiometricResult> =
            async(start = CoroutineStart.UNDISPATCHED) { gate.authenticate(promptText) }
        port.deliver(BiometricResult.Rejected)
        assertEquals(BiometricResult.Rejected, first.await())

        val second: Deferred<BiometricResult> =
            async(start = CoroutineStart.UNDISPATCHED) { gate.authenticate(promptText) }
        port.deliver(BiometricResult.Authenticated)

        assertEquals(BiometricResult.Authenticated, second.await())
        assertEquals(2, port.shown.size)
    }

    // --- cancellation -------------------------------------------------------------------------

    @Test
    fun `cancelling the caller dismisses the prompt`() = runTest {
        val port = FakePromptPort()
        val running: Deferred<BiometricResult> = authenticating(port)

        running.cancel()
        running.join()

        assertEquals(1, port.cancelCount)
    }

    @Test
    fun `a cancelled call produces no result and drops a late outcome`() = runTest {
        val port = FakePromptPort()
        var produced: BiometricResult? = null
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            produced = gate(port).authenticate(promptText)
        }

        running.cancel()
        running.join()
        // The framework callback can still fire once after cancelAuthentication; resuming a dead
        // continuation would throw rather than be ignored.
        port.deliver(BiometricResult.Authenticated)

        assertNull(produced, "cancellation is not an outcome")
    }

    @Test
    fun `a call that was never hosted needs no dismissal`() = runTest {
        val port = FakePromptPort(hasHost = false)

        gate(port).authenticate(promptText)

        assertEquals(0, port.cancelCount)
    }

    // --- ordering -----------------------------------------------------------------------------

    @Test
    fun `authenticate does not consult availability first`() = runTest {
        // Pre-checking would add a race — the state can change between the two calls — and would
        // hide a lockout, which is only discoverable by trying.
        var statusCalls = 0
        val port = FakePromptPort()
        val gate: BiometricGate = gate(
            port = port,
            status = BiometricStatusPort {
                statusCalls++
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
            },
        )

        val running: Deferred<BiometricResult> =
            async(start = CoroutineStart.UNDISPATCHED) { gate.authenticate(promptText) }
        port.deliver(BiometricResult.Authenticated)

        assertEquals(BiometricResult.Authenticated, running.await())
        assertEquals(0, statusCalls)
    }

    private fun kotlinx.coroutines.CoroutineScope.authenticating(
        port: BiometricPromptPort,
    ): Deferred<BiometricResult> =
        async(start = CoroutineStart.UNDISPATCHED) { gate(port).authenticate(promptText) }
}
