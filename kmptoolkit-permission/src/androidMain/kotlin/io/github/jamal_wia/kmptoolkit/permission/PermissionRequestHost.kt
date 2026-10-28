package io.github.jamal_wia.kmptoolkit.permission

import android.content.Intent

/**
 * The activity-side half of an Android request: it shows the system UI and reports the answer back.
 *
 * Both members map onto an `ActivityResultLauncher` — `RequestMultiplePermissions` and
 * `StartActivityForResult` — which belongs to an activity, while the controller outlives every
 * activity. Implement this yourself when your app already owns that plumbing; the controller only
 * ever has one request in flight, so an implementation needs at most one pending callback per member.
 *
 * The contract both members share:
 * - Call `onResult` **exactly once**, on any thread, after the UI has been answered. Never calling it
 *   leaves the requesting coroutine suspended until it is cancelled.
 * - Return `false` when nothing could be shown at all — no activity, no launcher — and then do **not**
 *   call `onResult`. The controller then reports the status unchanged rather than inventing a refusal.
 */
public interface PermissionRequestHost {

    /**
     * Shows one system permission dialog for all of [androidPermissions] —
     * `ActivityResultContracts.RequestMultiplePermissions()` — and later invokes [onResult] with each
     * one's answer.
     *
     * @param androidPermissions values from `android.Manifest.permission` (or another manifest
     *   permission), chosen by the controller for the API level — pass them through verbatim. A single
     *   permission arrives as a one-element list.
     * @param onResult each requested permission's grant state. An empty map — what the platform
     *   delivers for a request it interrupted — is read as "no answer", not as a refusal.
     */
    public fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean

    /**
     * Starts [intent] for a result — `ActivityResultContracts.StartActivityForResult()` — and later
     * invokes [onResult] with the activity's result code. The controller uses it for the screens that
     * answer with one: the role dialog, the device-admin activation screen.
     */
    public fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean
}
