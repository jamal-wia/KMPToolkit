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
 * The real controller, host and tracker with two resumed windows that rotate at random, a transient
 * trampoline activity that comes and goes, callers on other threads (single and batch requests) that give
 * up at random, and dialogs answered at random by whichever activity shows one. When it ends nothing may be
 * left behind: no registered callback or launched key on a window, no lifecycle observer, no listener on the
 * tracker, and the host must take one more request. Bounded in real time throughout.
 */
@RunWith(AndroidJUnit4::class)
class FullStackWindowsSoakTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val access = CountingAccess(real)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
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

    private fun resumedWindow(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    @Test
    fun `two rotating windows, a trampoline and callers that give up leave nothing registered and the host free`() {
        shadowOf(application).denyPermissions(camera, microphone)
        application.declareInManifest(camera, microphone)
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, true)
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(microphone, true)
        val left: ActivityController<ComponentActivity> = resumedWindow()
        val right: ActivityController<ComponentActivity> = resumedWindow()
        var trampoline: ActivityController<ComponentActivity>? = null
        val observersBefore: List<Int> = listOf(left, right).map { (it.get().lifecycle as LifecycleRegistry).observerCount }
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
        val looper = shadowOf(Looper.getMainLooper())
        val random = Random(2026)

        val callers: List<Job> = List(24) { caller ->
            scope.launch {
                val mine = Random(caller)
                repeat(4) {
                    withTimeoutOrNull(20L + mine.nextInt(180)) {
                        if (mine.nextBoolean()) {
                            controller.request(CommonPermission.Camera)
                        } else {
                            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))
                        }
                    }
                }
            }
        }

        val seenLast: MutableMap<ActivityController<*>, ShadowActivity.PermissionsRequest?> = mutableMapOf()
        val unanswered: MutableMap<ActivityController<*>, ShadowActivity.PermissionsRequest> = mutableMapOf()
        var answeredCount = 0
        val trail: MutableList<String> = mutableListOf()
        fun name(c: ActivityController<*>): String = (if (c === left) "L" else if (c === right) "R" else "T") + "#" + (System.identityHashCode(c.get()) % 1000)
        fun scan() {
            for (c in listOfNotNull(left, right, trampoline)) {
                val shown: ShadowActivity.PermissionsRequest? = shadowOf(c.get()).lastRequestedPermission
                if (shown != null && shown !== seenLast[c]) {
                    seenLast[c] = shown
                    unanswered[c] = shown
                    trail += "show ${name(c)} rc=${shown.requestCode}"
                }
            }
        }
        val deadline: Long = System.nanoTime() + 40_000_000_000L
        var quiet = 0
        var iterations = 0
        while (System.nanoTime() < deadline && quiet < 30 && iterations < 20_000) {
            iterations++
            looper.idle()
            scan()
            when (random.nextInt(20)) {
                0 -> if (!left.get().isFinishing) { trail += "rotate ${name(left)}"; left.recreate(); trail += "  -> ${name(left)}" }
                1 -> if (!right.get().isFinishing) { trail += "rotate ${name(right)}"; right.recreate(); trail += "  -> ${name(right)}" }
                2 -> if (trampoline == null) { val spawned = resumedWindow(); trampoline = spawned; trail += "spawn ${name(spawned)}" }
                3 -> trampoline?.let { t ->
                    trail += "end ${name(t)}"
                    t.get().finish()
                    t.pause().stop().destroy()
                    activities.remove(t)
                    unanswered.remove(t)
                    seenLast.remove(t)
                    trampoline = null
                }
            }
            val live: List<ActivityController<ComponentActivity>> = listOfNotNull(left, right, trampoline)
            for (c in live) {
                val shown: ShadowActivity.PermissionsRequest? = shadowOf(c.get()).lastRequestedPermission
                if (shown != null && shown !== seenLast[c]) {
                    seenLast[c] = shown
                    unanswered[c] = shown
                    trail += "show ${name(c)} rc=${shown.requestCode}"
                }
                val owed: ShadowActivity.PermissionsRequest? = unanswered[c]
                if (owed != null && random.nextInt(3) == 0) {
                    trail += "answer ${name(c)} rc=${owed.requestCode}"
                    answerDenied(c.get(), owed)
                    unanswered.remove(c)
                    answeredCount++
                }
            }
            quiet = if (callers.all { it.isCompleted }) quiet + 1 else 0
            Thread.sleep(1)
        }

        assertTrue(callers.all { it.isCompleted }, "${callers.count { !it.isCompleted }} callers never finished within the bound")
        assertTrue(answeredCount >= 5, "only $answeredCount dialogs were answered after $iterations iterations: the soak did not exercise the host")
        // One more request must get its dialog: the host is free.
        trampoline?.let { t -> t.get().finish(); t.pause().stop().destroy(); activities.remove(t); unanswered.remove(t); trampoline = null }
        val last: Job = scope.launch { controller.request(CommonPermission.Camera) }
        val endAt: Long = System.nanoTime() + 15_000_000_000L
        while (!last.isCompleted && System.nanoTime() < endAt) {
            looper.idle()
            scan()
            for (c in listOf(left, right)) {
                val shown: ShadowActivity.PermissionsRequest? = shadowOf(c.get()).lastRequestedPermission
                if (shown != null && shown !== seenLast[c]) {
                    seenLast[c] = shown
                    unanswered[c] = shown
                }
                unanswered.remove(c)?.let { answerDenied(c.get(), it) }
            }
            Thread.sleep(1)
        }
        assertTrue(last.isCompleted, "the final request never completed: the host or the controller is stuck; last events: ${trail.takeLast(12)}")
        looper.idle()
        for ((index, c) in listOf(left, right).withIndex()) {
            val sizes: Map<String, Int> = registrySizes(c.get().activityResultRegistry)
            assertEquals(0, sizes["keyToCallback"], "window $index: a callback is still registered after every request ended: $sizes")
            assertEquals(0, sizes["launchedKeys"], "window $index: a key is still launched after every request ended: $sizes")
            assertEquals(observersBefore[index], (c.get().lifecycle as LifecycleRegistry).observerCount, "window $index: lifecycle observers were left")
        }
        assertEquals(0, access.live, "resume listeners were left on the tracker")
    }
}
