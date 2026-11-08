package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * Thirty callers on other threads ask the real controller over the real host and tracker, give up after
 * random delays (leaving stale dialogs) while the main thread answers dialogs after random delays. At the
 * end nothing may be left: no registered callback, no launched key, no lifecycle observer, no listener on
 * the tracker, and a further request still gets its dialog. Every wait is bounded; the main thread only
 * pumps (it must run the controller's posted launches).
 */
@RunWith(AndroidJUnit4::class)
class ControllerHostSoakTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val access = CountingAccess(real)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        real.release()
        activities.forEach { it.close() }
    }

    private class CountingAccess(private val real: ActivityAccess) : ActivityAccess by real {
        @Volatile var live: Int = 0
        private val lock = Any()

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            synchronized(lock) { live++ }
            val subscription: ActivitySubscription = real.addOnActivityResumedListener(listener)
            var cancelled = false
            return object : ActivitySubscription {
                override fun cancel() {
                    synchronized(lock) {
                        if (!cancelled) {
                            cancelled = true
                            live--
                        }
                    }
                    subscription.cancel()
                }
            }
        }
    }

    private fun registrySizes(registry: ActivityResultRegistry): Map<String, Int> =
        ActivityResultRegistry::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                when (val value: Any? = field.get(registry)) {
                    is Map<*, *> -> field.name to value.size
                    is Collection<*> -> field.name to value.size
                    else -> null
                }
            }.toMap()

    private fun answerDenied(activity: Activity, request: ShadowActivity.PermissionsRequest) {
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `thirty callers that give up at random while dialogs are answered at random leave nothing registered`() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        // A refusal that still allows a dialog: every request shows one again.
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, true)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val registry: ActivityResultRegistry = screen.get().activityResultRegistry
        val observersBefore: Int = (screen.get().lifecycle as LifecycleRegistry).observerCount
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
        val looper = shadowOf(Looper.getMainLooper())
        val random = Random(11)

        val callers: List<Job> = List(30) { caller ->
            scope.launch {
                val mine = Random(caller)
                repeat(4) {
                    withTimeoutOrNull(5L + mine.nextInt(55)) { controller.request(CommonPermission.Camera) }
                }
            }
        }

        var lastAnswered: ShadowActivity.PermissionsRequest? = null
        var answeredAt: Long = 0
        val deadline: Long = System.nanoTime() + 40_000_000_000L
        var quiet = 0
        var answeredCount = 0
        while (System.nanoTime() < deadline && quiet < 30) {
            looper.idle()
            val request: ShadowActivity.PermissionsRequest? = shadowOf(screen.get()).lastRequestedPermission
            val now: Long = System.nanoTime()
            if (request != null && request !== lastAnswered && now - answeredAt > random.nextInt(0, 30) * 1_000_000L) {
                answerDenied(screen.get(), request)
                lastAnswered = request
                answeredAt = now
                answeredCount++
            }
            quiet = if (callers.all { it.isCompleted }) quiet + 1 else 0
            Thread.sleep(1)
        }

        assertTrue(callers.all { it.isCompleted }, "${callers.count { !it.isCompleted }} callers never finished within the bound")
        assertTrue(answeredCount >= 10, "only $answeredCount dialogs were answered: the soak did not exercise the host")
        looper.idle()
        shadowOf(screen.get()).lastRequestedPermission?.takeIf { it !== lastAnswered }?.let { answerDenied(screen.get(), it) }
        looper.idle()
        val sizes: Map<String, Int> = registrySizes(registry)
        assertEquals(0, sizes["keyToCallback"], "a callback is still registered after every request ended: $sizes")
        assertEquals(0, sizes["launchedKeys"], "a key is still launched after every request ended: $sizes")
        assertEquals(observersBefore, (screen.get().lifecycle as LifecycleRegistry).observerCount, "lifecycle observers were left on the activity")
        assertEquals(0, access.live, "resume listeners were left on the tracker")
    }
}
