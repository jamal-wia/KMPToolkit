package io.github.jamal_wia.kmptoolkit.biometric

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertNull
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

        val handle: PromptHandle? = port.show(promptText) { outcomes++ }

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

        assertNull(port.show(promptText) { })
    }
}
