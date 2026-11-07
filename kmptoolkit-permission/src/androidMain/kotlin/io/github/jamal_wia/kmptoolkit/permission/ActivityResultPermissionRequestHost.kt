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
 *   requests. An activity the system destroys to recreate it (a configuration change, a reclaim) is let
 *   go at its destroy: the request stays pending and the answer is picked up from the recreated
 *   activity when it resumes. An activity that another live one has covered stays registered until the
 *   request ends, because the answer is delivered to the registry the dialog was started from; the
 *   registration is dropped when the request ends, however the answer arrived.
 * - **A cancelled caller does not withdraw a dialog.** A system dialog already on screen cannot be
 *   taken back, so the request stays pending until the user answers (or the activity is finished) and
 *   the answer is then dropped. A launch in that window returns `false`, as for any pending request;
 *   the controller waits for the host to be free (bounded) before it launches for a request queued
 *   behind the cancelled one.
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

    /**
     * Unique per host and per process, so two hosts in one app never answer each other's requests and a
     * dead process's parked answer never matches a key of this one (see [PermissionHostKeys]).
     */
    private val keyPrefix: String = PermissionHostKeys.nextPrefix()

    private var pending: PendingRequest<*, *>? = null

    override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean =
        start(
            kind = "permissions",
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            input = androidPermissions.toTypedArray(),
            onResult = onResult,
            // What `settle` reads as "interrupted, record nothing".
            abandoned = emptyMap(),
        )

    override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean =
        start(
            kind = "result",
            contract = ActivityResultContracts.StartActivityForResult(),
            input = intent,
            onResult = { result: ActivityResult -> onResult(result.resultCode) },
            abandoned = ActivityResult(Activity.RESULT_CANCELED, null),
        )

    private fun <I, O> start(
        kind: String,
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
        val request = PendingRequest("$keyPrefix.$kind", contract, onResult, abandoned)
        pending = request
        val launched: Boolean = try {
            // null: the registry handed a parked answer to the new registration and the request is over
            // already. A key that was never used cannot hold one in this process, so this is defensive.
            request.attach(activity)?.launch(input)
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
     *
     * It is registered with every live activity it was shown from or moved to: a dialog started from
     * activity A is answered to A's registry even when another activity B has covered A since, so A's
     * registration has to stay until the request ends. An activity the system destroys to recreate it
     * is let go at its destroy instead (the saved registry gives the answer to its successor).
     */
    private inner class PendingRequest<I, O>(
        private val key: String,
        private val contract: ActivityResultContract<I, O>,
        private val onResult: (O) -> Unit,
        private val abandoned: O,
    ) {
        var resumes: ActivitySubscription? = null
        private val attachments: MutableList<Attachment> = mutableListOf()
        private var current: Attachment? = null

        /** The registration with one activity's registry, and the observer that lets go of it at that activity's destroy. */
        private inner class Attachment(val activity: ComponentActivity) {
            var launcher: ActivityResultLauncher<I>? = null

            val destroyWatch: LifecycleEventObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) onDestroyed(this)
            }

            fun release() {
                activity.lifecycle.removeObserver(destroyWatch)
                val registered: ActivityResultLauncher<I> = launcher ?: return
                launcher = null
                registered.unregister()
            }
        }

        /**
         * Another activity resumed. Android resumes the next screen before it stops and destroys the
         * one that finished, so by the time this runs the activity the dialog belonged to is already
         * finishing and its destroy event would find the watch removed by the move: its answer
         * can never be delivered, so give up here. A configuration change is not this case — the old
         * instance is not finishing.
         */
        fun onResumed(activity: ComponentActivity) {
            val leaving: Activity? = current?.activity
            if (leaving != null && leaving !== activity && leaving.isFinishing && !leaving.isChangingConfigurations) {
                finish()
                onResult(abandoned)
                return
            }
            attach(activity)
        }

        /**
         * Registers with [activity]'s registry, unless it already is. Returns `null` when the registry
         * delivered a parked answer from inside `register` and so ended the request.
         */
        fun attach(activity: ComponentActivity): ActivityResultLauncher<I>? {
            attachments.firstOrNull { it.activity === activity }?.let { known: Attachment ->
                current = known
                return known.launcher
            }
            // An activity destroyed since it was attached is no longer a registry anything can be answered to.
            attachments.filter { it.activity.isDestroyed }.forEach { gone: Attachment -> detach(gone) }
            val attachment = Attachment(activity)
            attachments += attachment
            current = attachment
            activity.lifecycle.addObserver(attachment.destroyWatch)
            val registered: ActivityResultLauncher<I> = activity.activityResultRegistry.register(key, contract) { output: O ->
                if (pending === this) {
                    finish()
                    onResult(output)
                }
            }
            attachment.launcher = registered
            // `register` hands a parked answer to the callback before it returns, so `finish` ran with no
            // launcher to unregister: do it now that there is one.
            if (pending !== this) {
                attachment.release()
                return null
            }
            return registered
        }

        /**
         * An activity that is finished, not recreated, while its dialog is up never hears the answer:
         * the registry that would deliver it is gone with it, and no other registry holds it. Left
         * alone, [pending] would refuse every later launch for the life of the process. An activity the
         * system destroys to recreate it (a configuration change, a reclaim) is only let go of: the
         * registry restores the answer, and the request stays pending until the next resume re-attaches.
         */
        private fun onDestroyed(attachment: Attachment) {
            if (pending !== this) return
            val activity: ComponentActivity = attachment.activity
            if (attachment === current && activity.isFinishing && !activity.isChangingConfigurations) {
                finish()
                onResult(abandoned)
            } else {
                detach(attachment)
            }
        }

        private fun detach(attachment: Attachment) {
            attachments.remove(attachment)
            if (current === attachment) current = null
            attachment.release()
        }

        fun finish() {
            pending = null
            resumes?.cancel()
            resumes = null
            val all: List<Attachment> = attachments.toList()
            attachments.clear()
            current = null
            all.forEach { it.release() }
        }
    }
}
