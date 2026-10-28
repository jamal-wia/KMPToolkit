package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import org.robolectric.Shadows.shadowOf

/**
 * Resume listeners as an `ActivityAccess` keeps them: [fire] is an activity resuming, [count] what is
 * still registered. [resumedNow] makes a newly added listener fire at once, as a real access does
 * while an activity is resumed.
 */
class ResumeListeners(var resumedNow: Boolean = false) {

    private val listeners: MutableList<() -> Unit> = mutableListOf()

    val count: Int get() = listeners.size

    fun add(listener: () -> Unit): ActivitySubscription {
        listeners += listener
        if (resumedNow) listener()
        return object : ActivitySubscription {
            override fun cancel() {
                listeners -= listener
            }
        }
    }

    fun fire() {
        listeners.toList().forEach { it() }
    }
}

/** A host whose answers the test dictates, recording every launch. */
open class RecordingHost(
    private val answers: (String) -> Boolean = { true },
    private val canLaunch: Boolean = true,
    private val throwOnLaunch: Boolean = false,
    private val answerThenReportFailure: Boolean = false,
    private val resultCode: Int? = null,
) : PermissionRequestHost {

    val groups: MutableList<List<String>> = mutableListOf()
    val intents: MutableList<Intent> = mutableListOf()

    val launchCount: Int get() = groups.size
    val launchedWith: String? get() = groups.lastOrNull()?.singleOrNull()

    override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
        groups += androidPermissions
        if (throwOnLaunch) error("the activity is gone")
        val answer: Map<String, Boolean> = androidPermissions.associateWith(answers)
        if (answerThenReportFailure) {
            onResult(answer)
            return false
        }
        if (!canLaunch) return false
        onResult(answer)
        return true
    }

    override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean {
        intents += intent
        if (throwOnLaunch) error("the activity is gone")
        val code: Int = resultCode ?: return false
        onResult(code)
        return true
    }
}

/** Makes this app's manifest declare exactly [permissions], as `PackageManager` reports it. */
fun Application.declareInManifest(vararg permissions: String) {
    val info: PackageInfo = shadowOf(packageManager).getInternalMutablePackageInfo(packageName)
    info.requestedPermissions = arrayOf(*permissions)
    info.requestedPermissionsFlags = IntArray(permissions.size)
}

/** An [AndroidPermissionController] with every seam defaulted to "nothing unusual". */
internal fun testController(
    context: Context,
    storage: KeyValueStorage,
    host: PermissionRequestHost = RecordingHost(),
    keyPrefix: String = "test",
    sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
    shouldShowRationale: (String) -> Boolean? = { false },
    awaitActivity: suspend () -> Unit = {},
    resumeListeners: ResumeListeners = ResumeListeners(),
    launchScreen: (SystemScreenRequest) -> Boolean = { true },
    disavowsLocationForScan: () -> Boolean = { true },
    declaredPermissions: () -> Set<String>? = { null },
    isRevokedByPolicy: (String) -> Boolean = { false },
    notificationsEnabled: () -> Boolean = { true },
    logger: Logger = NoopLogger,
): AndroidPermissionController = AndroidPermissionController(
    context = context,
    host = host,
    storage = storage,
    keyPrefix = keyPrefix,
    logger = logger,
    sdkInt = sdkInt,
    shouldShowRationale = shouldShowRationale,
    awaitActivity = awaitActivity,
    addResumedListener = resumeListeners::add,
    launchScreen = launchScreen,
    disavowsLocationForScan = disavowsLocationForScan,
    declaredPermissions = declaredPermissions,
    isRevokedByPolicy = isRevokedByPolicy,
    notificationsEnabled = notificationsEnabled,
    specialAccesses = SpecialAccesses(context, logger, declaredPermissions),
)

/** A permission type no controller knows: what a consumer-defined subtype would be. */
@OptIn(ToolkitInternalApi::class)
internal object ForeignPermission : Permission {
    override val id: String = "FOREIGN"
}
