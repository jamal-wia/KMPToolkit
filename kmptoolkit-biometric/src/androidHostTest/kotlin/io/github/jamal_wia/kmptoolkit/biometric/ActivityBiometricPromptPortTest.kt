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
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/**
 * The real port without a real prompt: the "nobody to show a prompt to" answers (both produce
 * [BiometricResult.NoPromptHost]), and the prompt handle's state machine and the show wiring, driven
 * through the factory and start seams because Robolectric has no biometric service to show a prompt.
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

    /** Counts the dismissals the handle asks for and where they ran. */
    private class FakeStop {
        var calls: Int = 0
        var allOnMain: Boolean = true
        val stop: (BiometricPrompt) -> Unit = {
            calls++
            if (Looper.myLooper() != Looper.getMainLooper()) allOnMain = false
        }
    }

    private class HandleFixture(val activity: FragmentActivity) {
        val outcomes = mutableListOf<BiometricResult>()
        val stop = FakeStop()
        val handle = ActivityPromptHandle({ outcomes += it }, stop.stop)
        fun newPrompt(): BiometricPrompt =
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {})
    }

    private fun fixture(): HandleFixture =
        HandleFixture(Robolectric.buildActivity(FragmentActivity::class.java).setup().get())

    @Test
    fun `a cancel before attach refuses the prompt and holds nothing`() {
        val f = fixture()

        f.handle.cancel()

        assertFalse(f.handle.attach(f.newPrompt()))
        assertFalse(f.handle.holdsPromptForTest())
        assertFalse(f.handle.holdsSinkForTest())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, f.stop.calls)
    }

    @Test
    fun `a cancel from another thread before attach refuses the prompt`() {
        val f = fixture()

        onBackground { f.handle.cancel() }

        assertFalse(f.handle.attach(f.newPrompt()))
        assertFalse(f.handle.holdsPromptForTest())
        assertFalse(f.handle.holdsSinkForTest())
    }

    @Test
    fun `a cancel after attach dismisses on main exactly once however often it is repeated`() {
        val f = fixture()
        assertTrue(f.handle.attach(f.newPrompt()))

        onBackground { f.handle.cancel() }
        assertEquals(0, f.stop.calls, "the dismissal is posted to main, not run on the cancelling thread")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, f.stop.calls)
        assertTrue(f.stop.allOnMain)

        f.handle.cancel()
        onBackground { f.handle.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, f.stop.calls)
        assertFalse(f.handle.holdsPromptForTest())
        assertFalse(f.handle.holdsSinkForTest())
    }

    @Test
    fun `a second attach does not replace the first prompt`() {
        val f = fixture()
        assertTrue(f.handle.attach(f.newPrompt()))

        assertFalse(f.handle.attach(f.newPrompt()))
    }

    @Test
    fun `the first outcome is delivered once and drops the prompt and the callback`() {
        val f = fixture()
        f.handle.attach(f.newPrompt())

        f.handle.deliver(BiometricResult.Authenticated)
        f.handle.deliver(BiometricResult.Rejected)

        assertEquals(listOf<BiometricResult>(BiometricResult.Authenticated), f.outcomes)
        assertFalse(f.handle.holdsPromptForTest())
        assertFalse(f.handle.holdsSinkForTest())
    }

    @Test
    fun `a rejection keeps the prompt but not the callback until the dismissal that follows`() {
        val f = fixture()
        f.handle.attach(f.newPrompt())

        f.handle.deliver(BiometricResult.Rejected)
        assertTrue(f.handle.holdsPromptForTest())
        assertFalse(f.handle.holdsSinkForTest())

        f.handle.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(f.handle.holdsPromptForTest())
        assertEquals(1, f.stop.calls)
        // The ERROR_CANCELED that the dismissal produces is dropped, not reported as the user cancelling.
        f.handle.deliver(BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED))
        assertEquals(listOf<BiometricResult>(BiometricResult.Rejected), f.outcomes)
    }

    @Test
    fun `an outcome after cancel is dropped`() {
        val f = fixture()
        f.handle.attach(f.newPrompt())

        f.handle.cancel()
        f.handle.deliver(BiometricResult.Authenticated)

        assertEquals(emptyList(), f.outcomes)
    }

    @Test
    fun `an outcome that wins over a cancel is delivered once and the cancel dismisses nothing`() {
        val f = fixture()
        f.handle.attach(f.newPrompt())

        f.handle.deliver(BiometricResult.Authenticated)
        onBackground { f.handle.cancel() }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<BiometricResult>(BiometricResult.Authenticated), f.outcomes)
        assertEquals(0, f.stop.calls)
    }

    @Test
    fun `an outcome before attach is delivered once and refuses the prompt`() {
        val f = fixture()

        f.handle.deliver(BiometricResult.Failed())
        f.handle.deliver(BiometricResult.Authenticated)

        assertEquals(listOf<BiometricResult>(BiometricResult.Failed()), f.outcomes)
        assertFalse(f.handle.attach(f.newPrompt()))
    }

    @Test
    fun `the prompt handle has no continuation field`() {
        // The behavioural tests above prove what is released; this guards the shape: the handle's
        // only state is the phase flow, never a continuation of the caller.
        val offending: List<String> = ActivityPromptHandle::class.java.declaredFields
            .filter { kotlin.coroutines.Continuation::class.java.isAssignableFrom(it.type) }
            .map { it.name }

        assertEquals(emptyList(), offending)
    }

    @Test
    fun `the caller callback is not reachable from the framework callback after an outcome`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val callerCallback: (BiometricResult) -> Unit = { }
        portWith(activity, seams).show(promptText, true, callerCallback)
        val framework: BiometricPrompt.AuthenticationCallback = checkNotNull(seams.callback)
        // The walker must see the callback while it is still held, or "not reachable" proves nothing.
        assertTrue(reaches(framework, callerCallback))

        framework.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "locked out")

        assertFalse(reaches(framework, callerCallback))
    }

    /** Whether [target] is reachable from [from] through instance fields of non-framework objects. */
    private fun reaches(from: Any, target: Any): Boolean {
        val seen: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        val queue = ArrayDeque<Any>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val current: Any = queue.removeFirst()
            if (!seen.add(current)) continue
            var type: Class<*>? = current.javaClass
            while (type != null && !type.name.startsWith("java.lang.Object")) {
                for (field in type.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    val value: Any = field.get(current) ?: continue
                    if (value === target) return true
                    val name: String = value.javaClass.name
                    if (!name.startsWith("java.") && !name.startsWith("android") && !name.startsWith("kotlin.Pair")) {
                        queue.add(value)
                    }
                }
                type = type.superclass
            }
        }
        return false
    }

    // --- the show() wiring ------------------------------------------------------------------

    @Test
    fun `an outcome from the framework callback releases the handle that show returned`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        var callback: BiometricPrompt.AuthenticationCallback? = null
        val port = ActivityBiometricPromptPort(
            FakeActivityAccess(activity),
            BiometricGateConfig(),
            promptFactory = { a, executor, cb ->
                callback = cb
                BiometricPrompt(a, executor, cb)
            },
            startPrompt = { _, _ -> },
        )
        val outcomes = mutableListOf<BiometricResult>()

        val handle = port.show(promptText, requireConfirmation = true) { outcomes += it } as ActivityPromptHandle

        assertTrue(handle.holdsPromptForTest())
        val captured: BiometricPrompt.AuthenticationCallback = checkNotNull(callback)
        captured.onAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        captured.onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "again")

        assertEquals(1, outcomes.size)
        assertFalse(handle.holdsPromptForTest())
    }

    @Test
    fun `a prompt that fails to start reports Failed and releases the handle`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val port = ActivityBiometricPromptPort(
            FakeActivityAccess(activity),
            BiometricGateConfig(),
            startPrompt = { _, _ -> error("rejected by the builder") },
        )
        val outcomes = mutableListOf<BiometricResult>()

        val handle = port.show(promptText, requireConfirmation = true) { outcomes += it } as ActivityPromptHandle

        assertEquals(listOf<BiometricResult>(BiometricResult.Failed()), outcomes)
        assertFalse(handle.holdsPromptForTest())
    }

    // --- cancellation racing the show block (invariant: a cancel during the show start never leaves a sheet) ---

    /** A port whose factory and start seams only count, so no real prompt is built or started. */
    private class CountingSeams {
        var created: Int = 0
        var started: Int = 0
        var onCreate: () -> Unit = { }
        var callback: BiometricPrompt.AuthenticationCallback? = null
        val stop = FakeStop()
    }

    private fun portWith(activity: FragmentActivity, seams: CountingSeams): ActivityBiometricPromptPort =
        ActivityBiometricPromptPort(
            FakeActivityAccess(activity),
            BiometricGateConfig(),
            promptFactory = { a, executor, cb ->
                seams.created++
                seams.callback = cb
                seams.onCreate()
                BiometricPrompt(a, executor, cb)
            },
            startPrompt = { _, _ -> seams.started++ },
            stopPrompt = seams.stop.stop,
        )

    /** Runs [block] on a background thread and waits for it, bounded so a hang fails fast. */
    private fun <T> onBackground(block: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return executor.submit<T>(block).get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a show posted from another thread and cancelled before it runs never builds a prompt`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val outcomes = mutableListOf<BiometricResult>()
        val handle: PromptHandle = onBackground {
            checkNotNull(portWith(activity, seams).show(promptText, true) { outcomes += it })
        }

        handle.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, seams.created)
        assertEquals(0, seams.started)
        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun `a cancel landing while the prompt is being built prevents authenticate`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val handle: PromptHandle = onBackground { checkNotNull(portWith(activity, seams).show(promptText, true) { }) }
        // The caller is cancelled in the window between the show block's pre-check and its attach.
        seams.onCreate = { handle.cancel() }

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, seams.created)
        assertEquals(0, seams.started, "the sheet must not be started once cancelled")
        assertEquals(0, seams.stop.calls, "nothing was shown, so nothing is dismissed")
    }

    @Test
    fun `a cancel from another thread after the block ran dismisses the sheet once on main and drops the outcome`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val outcomes = mutableListOf<BiometricResult>()
        val handle: PromptHandle = onBackground {
            checkNotNull(portWith(activity, seams).show(promptText, true) { outcomes += it })
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, seams.started)

        onBackground { handle.cancel() }
        assertEquals(0, seams.stop.calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, seams.stop.calls)
        assertTrue(seams.stop.allOnMain)
        // The dismissal's own callback arrives after cancel: PromptHandle.cancel promises no outcome.
        checkNotNull(seams.callback).onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "cancelled")

        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun `an outcome that arrives after cancel is dropped`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val outcomes = mutableListOf<BiometricResult>()
        val handle: PromptHandle = checkNotNull(portWith(activity, seams).show(promptText, true) { outcomes += it })

        handle.cancel()
        checkNotNull(seams.callback).onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "locked out")

        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun `cancel is idempotent in every phase`() {
        val activity: FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val seams = CountingSeams()
        val handle: PromptHandle = checkNotNull(portWith(activity, seams).show(promptText, true) { })

        handle.cancel()
        handle.cancel()
        onBackground { handle.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        handle.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, seams.stop.calls)
    }
}
