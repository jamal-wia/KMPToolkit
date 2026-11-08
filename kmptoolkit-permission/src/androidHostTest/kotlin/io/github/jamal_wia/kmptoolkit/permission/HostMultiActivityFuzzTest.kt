package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The real host with the dialog up and other activities of the app (trampolines, deep-link screens) coming
 * and going around the activity the dialog was shown from: pause, resume, rotation of that activity, other
 * activities starting, finishing and being destroyed. Written from the host's stated contract: the request
 * ends when (and only when) the activity the dialog came from is finished, and then exactly once, empty;
 * otherwise it stays pending until the user answers, and then resolves once with that answer. The host
 * takes the next request afterwards. Seeded and capped: 60 walks of at most ten events.
 */
@RunWith(AndroidJUnit4::class)
class HostMultiActivityFuzzTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    @Test
    fun `other activities coming and going around the dialog's activity never end the request, only its own finish does`() {
        shadowOf(application).denyPermissions(camera)
        val random = Random(77)
        repeat(60) { walk ->
            val finishWalk: Boolean = random.nextBoolean()
            val origin: ActivityController<ComponentActivity> = resumedActivity()
            val host = ActivityResultPermissionRequestHost(access)
            val answers: MutableList<Map<String, Boolean>> = mutableListOf()
            assertTrue(host.launch(listOf(camera)) { answers += it }, "walk $walk: not launched")
            val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(origin.get()).lastRequestedPermission)
            var originResumed = true
            val others: MutableList<ActivityController<ComponentActivity>> = mutableListOf()
            val otherLive: MutableList<Boolean> = mutableListOf()
            val trail: MutableList<String> = mutableListOf()

            fun answer() {
                endPlatformRequest(origin.get())
                @Suppress("DEPRECATION")
                origin.get().onRequestPermissionsResult(
                    request.requestCode,
                    request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_DENIED),
                )
                endPlatformRequest(origin.get())
            }

            repeat(random.nextInt(1, 11)) {
                when (random.nextInt(5)) {
                    0 -> if (originResumed) { trail += "pause"; origin.pause(); originResumed = false }
                    1 -> if (!originResumed) { trail += "resume"; origin.resume(); originResumed = true }
                    2 -> if (originResumed) { trail += "rotate"; origin.recreate() }
                    3 -> {
                        if (originResumed) { trail += "pause"; origin.pause(); originResumed = false }
                        trail += "spawn"
                        others += resumedActivity()
                        otherLive += true
                    }

                    else -> {
                        val k: Int = otherLive.indexOfFirst { it }
                        if (k >= 0) {
                            trail += "end-other"
                            val t: ActivityController<ComponentActivity> = others[k]
                            t.get().finish()
                            t.pause()
                            if (random.nextBoolean()) t.stop().destroy()
                            otherLive[k] = false
                        }
                    }
                }
                assertEquals(emptyList(), answers, "walk $walk $trail: the request ended without the dialog's activity finishing")
            }

            if (finishWalk) {
                trail += "finish-origin"
                origin.get().finish()
                if (originResumed) origin.pause()
                origin.stop().destroy()
                activities.remove(origin)
                assertEquals(listOf<Map<String, Boolean>>(emptyMap()), answers, "walk $walk $trail: the finished origin did not end the request exactly once")
            } else {
                if (!originResumed) origin.resume()
                trail += "answer"
                answer()
                assertEquals(listOf(mapOf(camera to false)), answers, "walk $walk $trail")
            }
            // The host takes the next request, from whatever is resumed.
            val next: ActivityController<ComponentActivity> = resumedActivity()
            assertTrue(host.launch(listOf(camera)) {}, "walk $walk $trail: the host stayed stuck")
            next.get().finish()
            next.pause().stop().destroy()
            activities.remove(next)
            others.forEachIndexed { k, t ->
                if (otherLive[k]) { t.get().finish(); t.pause().stop().destroy() }
            }
            if (!finishWalk) { origin.get().finish(); origin.pause().stop().destroy() }
            (others + origin + next).forEach { it.close(); activities.remove(it) }
        }
    }
}
