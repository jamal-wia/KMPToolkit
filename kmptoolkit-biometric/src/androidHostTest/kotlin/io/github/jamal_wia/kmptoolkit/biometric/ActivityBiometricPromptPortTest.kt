package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The "there is nobody to show a prompt to" half of the real port.
 *
 * Both branches produce the same `null` — and therefore [BiometricResult.NoPromptHost] — but for
 * different reasons, and both are reachable in a shipped app: the first every time something
 * authenticates from the background, the second whenever a consumer's activity does not extend
 * `FragmentActivity`, which the compiler cannot warn them about because the type never appears in
 * this module's public API.
 *
 * The success path is not testable here: showing the prompt means inflating a fragment that talks
 * to a biometric service Robolectric does not emulate.
 */
@RunWith(AndroidJUnit4::class)
class ActivityBiometricPromptPortTest {

    private val promptText = BiometricPromptText(
        title = "Unlock",
        subtitle = "Confirm it is you",
        cancelLabel = "Cancel",
    )

    private class FakeActivityAccess(private val activity: Activity?) : ActivityAccess {

        override fun <R> withActivity(block: (Activity) -> R): R? = activity?.let(block)

        override fun addOnActivityResumedListener(
            listener: (Activity) -> Unit,
        ): ActivitySubscription = object : ActivitySubscription {
            override fun cancel(): Unit = Unit
        }

        override fun release(): Unit = Unit
    }

    @Test
    fun `no resumed activity means no prompt and no outcome`() {
        val port = ActivityBiometricPromptPort(FakeActivityAccess(null), BiometricGateConfig())
        var outcomes = 0

        val handle: PromptHandle? = port.show(promptText, requireConfirmation = true) { outcomes++ }

        assertNull(handle)
        // Nothing was shown, so nothing may be reported: the gate turns the null into NoPromptHost
        // itself, and a callback here would be a second, contradictory answer.
        assertNull(outcomes.takeIf { it != 0 })
    }

    @Test
    fun `an activity that is not a FragmentActivity cannot host the prompt`() {
        // androidx.biometric's prompt is a fragment. A plain Activity is a wiring mistake, and it
        // must surface as NoPromptHost rather than as a ClassCastException.
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val port = ActivityBiometricPromptPort(FakeActivityAccess(activity), BiometricGateConfig())

        assertNull(port.show(promptText, requireConfirmation = true) { })
    }

    @Test
    fun `a ComponentActivity cannot host the prompt either`() {
        // The case the documentation once got wrong: ComponentActivity is FragmentActivity's
        // superclass, not a subclass, and it is what a Compose activity extends by default.
        val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val port = ActivityBiometricPromptPort(FakeActivityAccess(activity), BiometricGateConfig())

        assertNull(port.show(promptText, requireConfirmation = true) { })
    }

    // --- what the handle keeps alive --------------------------------------------------------

    @Test
    fun `the prompt handle has no activity field`() {
        // The class KDoc promises the activity is never stored. A field of any Context type is the
        // only way the handle could pin one, so its absence is checkable without a garbage collector.
        val offending: List<String> = ActivityPromptHandle::class.java.declaredFields
            .filter { android.content.Context::class.java.isAssignableFrom(it.type) }
            .map { it.name }

        assertEquals(emptyList(), offending)
    }

    @Test
    fun `a released handle lets go of the system prompt`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val handle = ActivityPromptHandle()
        handle.attach(BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), noopCallback))
        assertTrue(handle.holdsPromptForTest())

        handle.release()

        assertFalse(handle.holdsPromptForTest())
    }

    @Test
    fun `a prompt attached after release is not kept`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val handle = ActivityPromptHandle()
        handle.release()

        handle.attach(BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), noopCallback))

        assertFalse(handle.holdsPromptForTest())
    }

    @Test
    fun `cancel before attach is remembered and cancel after release is harmless`() {
        val handle = ActivityPromptHandle()
        handle.cancel()
        assertTrue(handle.isCancelled)

        handle.release()
        handle.cancel()
    }

    @Test
    fun `the first delivered outcome releases the prompt and a second is dropped`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val handle = ActivityPromptHandle()
        handle.attach(BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), noopCallback))
        val outcomes = mutableListOf<BiometricResult>()
        val deliver: (BiometricResult) -> Unit = deliverOnceAndRelease(handle) { outcomes += it }

        deliver(BiometricResult.Authenticated)
        deliver(BiometricResult.Rejected)

        assertEquals(listOf<BiometricResult>(BiometricResult.Authenticated), outcomes)
        assertFalse(handle.holdsPromptForTest())
    }

    @Test
    fun `a rejection keeps the prompt for the dismissal that follows it and cancel then releases it`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val handle = ActivityPromptHandle()
        handle.attach(BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), noopCallback))
        val deliver: (BiometricResult) -> Unit = deliverOnceAndRelease(handle) { }

        deliver(BiometricResult.Rejected)
        assertTrue(handle.holdsPromptForTest())

        handle.cancel()
        assertFalse(handle.holdsPromptForTest())
    }

    private val noopCallback = object : BiometricPrompt.AuthenticationCallback() {}
}
