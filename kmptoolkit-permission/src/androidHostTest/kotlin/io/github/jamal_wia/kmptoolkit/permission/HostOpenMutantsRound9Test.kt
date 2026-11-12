package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** A component activity whose task id the test chooses: Robolectric gives every activity the same one. */
class TaskedActivity : ComponentActivity() {
    var task: Int = 1

    override fun getTaskId(): Int = task
}

/** Counts the resume listeners that are subscribed and not yet cancelled. */
private class CountingAccess(private val inner: ActivityAccess) : ActivityAccess by inner {
    var live: Int = 0
    var added: Int = 0

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        live++
        added++
        val subscription: ActivitySubscription = inner.addOnActivityResumedListener(listener)
        var cancelled = false
        return object : ActivitySubscription {
            override fun cancel() {
                if (!cancelled) {
                    cancelled = true
                    live--
                }
                subscription.cancel()
            }
        }
    }
}

/**
 * Round 9 (E1, bm): the two host mutants round 8 left open.
 *
 * H2 - the host checks that its request is still pending before it subscribes to resumes, because a launch can
 * end the request already (a parked answer handed over from inside `register`). Without the check a finished request
 * leaves a resume subscription behind for good. The parked answer is produced by predicting the next host's key
 * (`PermissionHostKeys` numbers hosts from a per-process token).
 *
 * H8 - after the origin activity was lost to a recreation, only the next activity of the same class in the same task
 * becomes the origin. Robolectric gives every activity task id 0; [TaskedActivity] overrides `getTaskId`.
 */
@RunWith(AndroidJUnit4::class)
class HostOpenMutantsRound9Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    private fun newAccess(): ActivityAccess = createActivityAccess(application).also { accesses += it }

    private fun tasked(task: Int): ActivityController<TaskedActivity> =
        Robolectric.buildActivity(TaskedActivity::class.java).also {
            it.get().task = task
            activities += it
        }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    // --- H2 ---------------------------------------------------------------------------------------

    @Test
    fun `a request ended inside the launch by a parked answer leaves no resume subscription`() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        PermissionHostKeys.restartProcessForTest()
        val taken: String = PermissionHostKeys.nextPrefix()
        val nextHostKey: String = taken.removeSuffix("-1") + "-2.permissions"
        val access = CountingAccess(newAccess())
        val host = ActivityResultPermissionRequestHost(access) // takes host number 2
        val activity: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

        // Park an answer under the key the host is about to use: launched under that key, unregistered, answered.
        val parking = activity.get().activityResultRegistry.register(
            nextHostKey,
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        parking.launch(arrayOf(camera))
        val asked = assertNotNull(shadowOf(activity.get()).lastRequestedPermission)
        parking.unregister()
        endPlatformRequest(activity.get())
        @Suppress("DEPRECATION")
        activity.get().onRequestPermissionsResult(
            asked.requestCode,
            asked.requestedPermissions,
            IntArray(asked.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )

        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val launched: Boolean = host.launch(listOf(camera)) { answers += it }

        assertTrue(launched)
        assertEquals(listOf(mapOf(camera to false)), answers, "the parked answer ends the request inside the launch")
        assertEquals(0, access.live, "a request that is over must not leave a resume subscription behind")
        // The host is free again and a request of its own works.
        val second: MutableList<Map<String, Boolean>> = mutableListOf()
        assertTrue(host.launch(listOf(camera)) { second += it })
        assertEquals(1, access.live, "an ordinary pending request holds exactly one subscription")
        activity.pause().resume()
    }

    // --- H8 ---------------------------------------------------------------------------------------

    private fun hostWithLostOrigin(taskOfNext: Int, answers: MutableList<Map<String, Boolean>>): ActivityController<TaskedActivity> {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        val access: ActivityAccess = newAccess()
        val host = ActivityResultPermissionRequestHost(access)
        val origin: ActivityController<TaskedActivity> = tasked(1).setup()
        assertTrue(host.launch(listOf(camera)) { answers += it })
        // The system reclaims the origin (not finishing, not a configuration change): the request stays pending.
        origin.pause().stop().destroy()
        assertTrue(answers.isEmpty())
        return tasked(taskOfNext).setup()
    }

    @Test
    fun `a same class activity of another task that resumes after the origin was lost never becomes the origin`() {
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val stranger: ActivityController<TaskedActivity> = hostWithLostOrigin(taskOfNext = 2, answers = answers)

        // It is not the recreated origin, so its finishing does not end the request.
        stranger.get().finish()
        stranger.pause().stop().destroy()

        assertNull(answers.firstOrNull(), "an activity of another task ended the request: $answers")
    }

    @Test
    fun `control - a same class activity of the same task that resumes after the origin was lost is the origin and its finish ends the request`() {
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val recreated: ActivityController<TaskedActivity> = hostWithLostOrigin(taskOfNext = 1, answers = answers)

        recreated.get().finish()
        recreated.pause().stop().destroy()

        assertEquals(listOf(emptyMap<String, Boolean>()), answers)
    }

    @Test
    fun `the real recreation of the lost origin still takes over after a stranger of another task came and went`() {
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val stranger: ActivityController<TaskedActivity> = hostWithLostOrigin(taskOfNext = 2, answers = answers)
        stranger.pause().stop().destroy()
        assertTrue(answers.isEmpty())

        val recreated: ActivityController<TaskedActivity> = tasked(1).setup()
        recreated.get().finish()
        recreated.pause().stop().destroy()

        assertEquals(listOf(emptyMap<String, Boolean>()), answers, "the recreated origin of the same task still counts")
    }

    @Test
    fun `an intent started for result keeps the same origin rule`() {
        shadowOf(application).denyPermissions(camera)
        val access: ActivityAccess = newAccess()
        val host = ActivityResultPermissionRequestHost(access)
        val origin: ActivityController<TaskedActivity> = tasked(1).setup()
        val results: MutableList<Int?> = mutableListOf()
        assertTrue(host.launchForResultOrInterrupted(Intent("android.settings.SETTINGS")) { results += it })
        origin.pause().stop().destroy()

        val stranger: ActivityController<TaskedActivity> = tasked(2).setup()
        stranger.get().finish()
        stranger.pause().stop().destroy()

        assertTrue(results.isEmpty(), "a stranger of another task interrupted the screen request: $results")
    }
}
