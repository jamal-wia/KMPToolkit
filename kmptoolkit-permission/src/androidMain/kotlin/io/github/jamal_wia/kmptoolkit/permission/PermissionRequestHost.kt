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
 * The handler asks for one permission at a time, so there is no multi-permission variant to
 * implement. That is a consequence of the [Permission] catalog: no entry in it maps to more than
 * one Android permission string.
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
}
