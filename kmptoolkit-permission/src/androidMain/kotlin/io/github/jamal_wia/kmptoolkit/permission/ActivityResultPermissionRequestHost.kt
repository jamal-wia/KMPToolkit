package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library's own [PermissionRequestHost]: it shows each dialog from the `ComponentActivity`
 * [activityAccess] reports as resumed, through that activity's `ActivityResultRegistry`, and needs no
 * wiring in your activities at all. It is what `createPermissionController` uses unless you pass a
 * host of your own.
 *
 * - **It survives the activity being recreated** while the dialog is up — a rotation, a configuration
 *   change, the system reclaiming the activity. The registry keeps the pending request across the
 *   recreation, and this host registers for the answer again on the next activity that resumes, so the
 *   answer still reaches the waiting request. (Not across process death: nothing in the new process
 *   is waiting.)
 * - **It gives up when the activity is finished** (not recreated) while the dialog is up — whether it
 *   is destroyed first or another activity resumes before it is: nothing can
 *   answer then, so the request ends as interrupted — an empty answer, `RESULT_CANCELED` — and the
 *   host takes the next one.
 * - **One request at a time.** A launch while another is pending returns `false` and shows nothing.
 *   The controller serializes its own requests, so this only refuses a second controller sharing the
 *   host — or a host-level bug.
 * - **Holds an activity only while its dialog is showing.** It asks [activityAccess] for the resumed
 *   one at each launch and keeps that activity with the registration (which belongs to its registry)
 *   until the dialog's answer arrives or the request is abandoned as above; nothing is kept between
 *   requests.
 * - **A cancelled caller does not withdraw a dialog.** A system dialog already on screen cannot be
 *   taken back, so the request stays pending until the user answers (or the activity is finished) and
 *   the answer is then dropped. A launch in that window returns `false`, as for any pending request.
 *
 * A launch returns `false` when no activity is resumed, or the resumed one is not a
 * `ComponentActivity` — every `AppCompatActivity` and Compose `ComponentActivity` is one.
 *
 * Call it on the main thread, as the controller does.
 *
 * @param activityAccess where the resumed activity comes from — normally the same access the
 *   controller is given.
 * @param logger where a launch that could not be made is reported.
 */
public class ActivityResultPermissionRequestHost(
    private val activityAccess: ActivityAccess,
    private val logger: Logger = NoopLogger,
) : PermissionRequestHost {

    /** Unique per host, so two hosts in one app never answer each other's requests. */
    private val keyPrefix: String = "io.github.jamal_wia.kmptoolkit.permission.host#${instances.incrementAndGet()}"

    private var pending: PendingRequest<*, *>? = null

    override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean =
        start(
            key = "$keyPrefix.permissions",
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            input = androidPermissions.toTypedArray(),
            onResult = onResult,
            // What `settle` reads as "interrupted, record nothing".
            abandoned = emptyMap(),
        )

    override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean =
        start(
            key = "$keyPrefix.result",
            contract = ActivityResultContracts.StartActivityForResult(),
            input = intent,
            onResult = { result: ActivityResult -> onResult(result.resultCode) },
            abandoned = ActivityResult(Activity.RESULT_CANCELED, null),
        )

    private fun <I, O> start(
        key: String,
        contract: ActivityResultContract<I, O>,
        input: I,
        onResult: (O) -> Unit,
        abandoned: O,
    ): Boolean {
        if (pending != null) {
            logger.w { "A permission request is already showing; not launching another" }
            return false
        }
        val activity: ComponentActivity = activityAccess.withActivity { it as? ComponentActivity } ?: run {
            logger.w { "No resumed ComponentActivity to show the permission request from" }
            return false
        }
        val request = PendingRequest(key, contract, onResult, abandoned)
        pending = request
        val launched: Boolean = try {
            request.attach(activity).launch(input)
            true
        } catch (cause: Exception) {
            logger.w(cause) { "Launching the permission request threw" }
            false
        }
        if (!launched) {
            request.finish()
            return false
        }
        // A request answered synchronously (every permission already granted) is finished already.
        if (pending === request) {
            request.resumes = activityAccess.addOnActivityResumedListener { resumed ->
                if (pending === request && resumed is ComponentActivity) request.onResumed(resumed)
            }
        }
        return true
    }

    /**
     * One request in flight. After a recreation the new activity's registry holds the answer under
     * [key] until something registers for it there; [attach] is that registration.
     */
    private inner class PendingRequest<I, O>(
        private val key: String,
        private val contract: ActivityResultContract<I, O>,
        private val onResult: (O) -> Unit,
        private val abandoned: O,
    ) {
        var resumes: ActivitySubscription? = null
        private var attachedTo: Activity? = null
        private var destroyWatch: LifecycleEventObserver? = null
        private var launcher: ActivityResultLauncher<I>? = null

        /**
         * Another activity resumed. Android resumes the next screen before it stops and destroys the
         * one that finished, so by the time this runs the activity the dialog belonged to is already
         * finishing and its destroy event would find [watchDestroy] removed by the move: its answer
         * can never be delivered, so give up here. A configuration change is not this case — the old
         * instance is not finishing.
         */
        fun onResumed(activity: ComponentActivity) {
            val leaving: Activity? = attachedTo
            if (leaving != null && leaving !== activity && leaving.isFinishing && !leaving.isChangingConfigurations) {
                finish()
                onResult(abandoned)
                return
            }
            attach(activity)
        }

        fun attach(activity: ComponentActivity): ActivityResultLauncher<I> {
            launcher?.takeIf { attachedTo === activity }?.let { return it }
            unwatch()
            attachedTo = activity
            watchDestroy(activity)
            return activity.activityResultRegistry.register(key, contract) { output: O ->
                if (pending !== this) return@register
                finish()
                onResult(output)
            }.also { launcher = it }
        }

        /**
         * An activity that is finished, not recreated, while its dialog is up never hears the answer:
         * the registry that would deliver it is gone with it, and no other registry holds it. Left
         * alone, [pending] would refuse every later launch for the life of the process. A system-
         * reclaimed or configuration-change destroy is not this case: the registry restores the answer.
         */
        private fun watchDestroy(activity: ComponentActivity) {
            val watch = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && activity.isFinishing && !activity.isChangingConfigurations &&
                    pending === this
                ) {
                    finish()
                    onResult(abandoned)
                }
            }
            destroyWatch = watch
            activity.lifecycle.addObserver(watch)
        }

        private fun unwatch() {
            val watch: LifecycleEventObserver = destroyWatch ?: return
            (attachedTo as? ComponentActivity)?.lifecycle?.removeObserver(watch)
            destroyWatch = null
        }

        fun finish() {
            unwatch()
            pending = null
            resumes?.cancel()
            resumes = null
            launcher?.unregister()
            launcher = null
            attachedTo = null
        }
    }

    private companion object {
        val instances: AtomicInteger = AtomicInteger(0)
    }
}
