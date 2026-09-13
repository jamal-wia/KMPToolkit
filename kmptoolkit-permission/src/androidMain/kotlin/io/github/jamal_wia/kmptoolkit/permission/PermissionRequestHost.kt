package io.github.jamal_wia.kmptoolkit.permission

/**
 * The activity-side half of an Android permission request: it launches the system dialog and
 * reports the answer back.
 *
 * You implement this, in about ten lines, on top of
 * `registerForActivityResult(ActivityResultContracts.RequestPermission())`. It is not done for you
 * because an `ActivityResultLauncher` must be registered *before* the activity reaches `RESUMED`
 * and dies with that activity. A library object that registered one would have to hold an
 * `Activity` for a lifetime the library controls, which is exactly the coupling that produces the
 * leak everyone eventually finds in a heap dump. Registering it in your own activity leaves the
 * activity reference where the framework already manages it.
 *
 * ```kotlin
 * class MainActivity : ComponentActivity(), PermissionRequestHost {
 *
 *     private var pending: ((Boolean) -> Unit)? = null
 *
 *     private val launcher = registerForActivityResult(
 *         ActivityResultContracts.RequestPermission(),
 *     ) { granted ->
 *         pending?.invoke(granted)
 *         pending = null
 *     }
 *
 *     override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean {
 *         pending = onResult
 *         return runCatching { launcher.launch(androidPermission) }.isSuccess
 *     }
 * }
 * ```
 *
 * [Permission.LOCATION] is requested as two Android permissions in one dialog, which needs the
 * multi-permission [launch] as well — the same ten lines on
 * `ActivityResultContracts.RequestMultiplePermissions()`. A host that does not implement it can still
 * request every other permission; a location request then shows nothing and reports the status
 * unchanged.
 */
public interface PermissionRequestHost {

    /**
     * Shows the system dialog for [androidPermission] and later invokes [onResult].
     *
     * @param androidPermission a value from `android.Manifest.permission`, chosen by the handler
     *   for the [Permission] being requested — including the API-level-dependent choices, so pass
     *   it through verbatim.
     * @param onResult must be called exactly once, with whether the user granted it. Never calling
     *   it leaves the requesting coroutine suspended until it is cancelled.
     * @return `false` if the dialog could not be shown at all — the activity is gone, the launcher
     *   was never registered. [onResult] must then **not** be called, and the handler reports the
     *   status unchanged rather than inventing a denial.
     */
    public fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean

    /**
     * Shows one system dialog for all of [androidPermissions] and later invokes [onResult] with each
     * one's answer — `registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`.
     *
     * The handler calls this only for a permission that has to be requested as a group in one dialog,
     * which today is [Permission.LOCATION]'s fine and coarse pair; everything else goes through the
     * single-permission [launch].
     *
     * The default forwards a one-element list to the single-permission [launch] and returns `false`
     * for anything larger, so a host written before this member existed keeps working for every
     * permission except location.
     *
     * @param onResult must be called exactly once, with an entry per requested permission.
     * @return `false` if the dialog could not be shown at all; [onResult] must then not be called.
     * @since 1.5.0
     */
    public fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
        val single: String = androidPermissions.singleOrNull() ?: return false
        return launch(single) { granted -> onResult(mapOf(single to granted)) }
    }
}
