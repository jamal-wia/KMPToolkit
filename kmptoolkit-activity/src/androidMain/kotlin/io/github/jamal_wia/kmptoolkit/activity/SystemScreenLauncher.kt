package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo

/**
 * Decides how a library opens a system screen — a Settings page, the biometric enrolment wizard, the
 * app-details page — and which task that screen lands in.
 *
 * Every `kmptoolkit-*` module that opens such a screen builds a [SystemScreenRequest] and hands it
 * to a launcher; nothing else about the launch is hard-coded. Pick a preset, or write your own:
 *
 * - [SeparateTask] — the screen gets a task of its own, never joins yours, and never joins a Settings
 *   task left in the background. The default wherever a module has no activity to launch from.
 * - [callerTask] — the screen is pushed onto your app's task from the resumed activity, so Back
 *   returns to the screen that asked, and a two-pane tablet shows it in a single pane. Falls back to
 *   [SeparateTask] when there is no activity to launch from.
 * - Your own — add flags, launch through an `ActivityResultLauncher` to get a result, open a kiosk
 *   allowlist window around the launch, log it, show an explanation first, or wrap a preset:
 *
 * ```kotlin
 * val logging = SystemScreenLauncher { request ->
 *     log("opening ${request.kind}")
 *     SystemScreenLauncher.SeparateTask.launch(request)
 * }
 * ```
 *
 * A module calls the launcher **once per logical request**, with every candidate intent in the
 * request, so a launcher that shows a dialog shows it once. Return `true` when a screen was handed to
 * the system and `false` when nothing was opened — the module maps `false` to its own "could not
 * open" answer. `true` means "handed to the system", not "the user saw it": Android 14+ can block a
 * background activity start without telling the caller. A launcher that throws is treated as
 * `false` by the calling module.
 *
 * There is no main-thread requirement on either preset: `startActivity` is safe from any thread. A
 * launcher of your own that touches UI switches threads itself — modules call it on whatever thread
 * their own caller used.
 *
 * @since 1.7.0
 */
public fun interface SystemScreenLauncher {

    /**
     * Opens the first candidate of [request] that the device can show.
     *
     * @return `true` if a screen was handed to the system, `false` if nothing was opened.
     */
    public fun launch(request: SystemScreenRequest): Boolean

    public companion object {

        /**
         * Opens the screen in a task of its own, from the application context, with
         * `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT`.
         *
         * `NEW_TASK` alone is not enough: it looks for an existing task with the screen's affinity —
         * for Settings, `com.android.settings` — and a Settings task opened earlier from a deep link
         * and left in the background would be brought forward, with the screen pushed on top of
         * whatever it held. Back, or the end of a wizard, would then land on that stale page instead
         * of your app. `NEW_DOCUMENT` skips the affinity lookup: it reuses a task only if one is rooted
         * at the very same screen and data, and starts a fresh one otherwise.
         *
         * Your task is never touched, which is what a lock-task (kiosk) app needs: a Settings record
         * can never end up inside the locked task.
         *
         * Remaining limits, both platform behaviour this flag set cannot change:
         * - The system strips `NEW_DOCUMENT` from a screen declared `singleTask`, `singleInstance` or
         *   `documentLaunchMode="never"`; such a screen behaves as with `NEW_TASK` alone.
         * - On a two-pane Settings (large screens, AOSP 12L+), a Settings page started with
         *   `NEW_TASK` hands itself to the Settings homepage, whose task may be a stale one. Use
         *   [callerTask] where that matters.
         */
        public val SeparateTask: SystemScreenLauncher = SystemScreenLauncher { request ->
            request.startFirstResolvable { intent ->
                request.applicationContext.startActivity(intent.addFlags(SEPARATE_TASK_FLAGS))
            }
        }

        /**
         * Opens the screen on your app's own task, from the activity [activityAccess] reports as
         * resumed, with no task flags — so the system Back button returns to that activity, the
         * screen shares your window in split screen, and a two-pane Settings shows it in a single
         * pane instead of handing it to its homepage.
         *
         * Falls back to [SeparateTask] when that cannot work: no activity is resumed (the app is in
         * the background, or between two activities), or the resumed one is declared
         * `singleInstance` — the system would add `NEW_TASK` to a launch from it anyway. A finishing
         * or destroyed activity is never reported by [ActivityAccess], so it falls back too.
         *
         * Not for a lock-task (kiosk) app: a screen on your task is inside the locked task, where the
         * lock-task allowlist no longer confines it.
         *
         * @param activityAccess the tracker to launch from; create it in `Application.onCreate`, see
         *   [createActivityAccess].
         */
        public fun callerTask(activityAccess: ActivityAccess): SystemScreenLauncher =
            SystemScreenLauncher { request ->
                val launched: Boolean? = activityAccess.withActivity { activity ->
                    if (activity.isSingleInstance()) {
                        null
                    } else {
                        request.startFirstResolvable { intent -> activity.startActivity(intent) }
                    }
                }
                launched ?: SeparateTask.launch(request)
            }
    }
}

/**
 * One logical request to open a system screen: the candidate intents to try, in order, and what the
 * screen is.
 *
 * The constructor is public so a launcher of your own can be tested, and so a wrapper can hand a
 * modified request to another launcher.
 *
 * @param candidates the intents to try, most specific first — several where the right screen depends
 *   on the device or its API level, as for biometric enrolment. They carry no launch flags: adding
 *   those is the launcher's decision. Copied on construction.
 * @param context any `Context`; only its application context is kept, so passing an `Activity` does
 *   not retain it.
 * @param kind which screen this is, for a launcher that treats screens differently or logs them.
 * @throws IllegalArgumentException if [candidates] is empty.
 * @since 1.7.0
 */
public class SystemScreenRequest(
    candidates: List<Intent>,
    context: Context,
    public val kind: SystemScreenKind,
) {

    /** The application context, for a launcher that starts a screen without an activity. */
    public val applicationContext: Context = context.applicationContext ?: context

    /** The candidate intents, in the order they should be tried. Never empty. */
    public val candidates: List<Intent> = candidates.toList()

    init {
        require(this.candidates.isNotEmpty()) { "A SystemScreenRequest needs at least one candidate intent" }
    }

    /**
     * Calls [start] with each candidate in order until one does not throw, and returns whether one
     * succeeded.
     *
     * A candidate that throws `ActivityNotFoundException` (the device has no such screen) or
     * `SecurityException` (the screen is not exported to this app) moves on to the next; any other
     * exception propagates. There is deliberately no `resolveActivity` pre-check: on Android 11+
     * package visibility can make it answer "no" for a Settings screen that does open.
     *
     * [start] receives a copy of the candidate, so it may add flags without changing this request.
     *
     * ```kotlin
     * // Launching for a result, from an ActivityResultLauncher<Intent> the app registered.
     * val forResult = SystemScreenLauncher { request -> request.startFirstResolvable { results.launch(it) } }
     * ```
     */
    public fun startFirstResolvable(start: (Intent) -> Unit): Boolean =
        candidates.any { candidate ->
            try {
                start(Intent(candidate))
                true
            } catch (notFound: ActivityNotFoundException) {
                false
            } catch (notExported: SecurityException) {
                false
            }
        }

    override fun toString(): String =
        "SystemScreenRequest(kind=$kind, candidates=${candidates.map { it.action }})"
}

/**
 * Which system screen a [SystemScreenRequest] opens.
 *
 * Deliberately open: each module declares its own kinds next to the API that opens them — for
 * example `BiometricEnrollmentScreen` in `kmptoolkit-biometric` — and later releases may add more.
 * A launcher that treats some screens differently matches the ones it knows and keeps an `else`
 * branch for the rest:
 *
 * ```kotlin
 * when (request.kind) {
 *     BiometricEnrollmentScreen -> kioskWindow.around { SystemScreenLauncher.SeparateTask.launch(request) }
 *     else -> SystemScreenLauncher.SeparateTask.launch(request)
 * }
 * ```
 *
 * `intent.action` is not a substitute: one logical screen can have candidates with different
 * actions, and matching raw Settings action strings ties your code to their spelling on each API
 * level.
 *
 * @since 1.7.0
 */
public interface SystemScreenKind

private const val SEPARATE_TASK_FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT

/**
 * A launch from a `singleInstance` activity always gets `NEW_TASK` added by the system, so a caller
 * task launch from it is not one. Unknown (the lookup failed) counts as not single-instance: the
 * launch still succeeds, only in its own task.
 */
private fun Activity.isSingleInstance(): Boolean = runCatching {
    packageManager.getActivityInfo(componentName, 0).launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
}.getOrDefault(false)
