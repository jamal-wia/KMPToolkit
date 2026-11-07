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
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The real host, the real activity tracker and the real controller together, under the hazards a
 * dialog meets in the field: rotation while it is up, a caller that walks away, an activity that is
 * finished with the dialog showing, and a process that dies and is rebuilt over the same storage.
 *
 * Everything runs on the test thread (the main thread under Robolectric) with an unconfined
 * dispatcher, so a request that never resumes shows as an incomplete `Deferred`, never as a hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ActivityResultPermissionRequestHostAdversarialTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val extraAccesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val logger = RecordingLogger()

    private fun logs(): String = logger.entries.joinToString(" | ") { it.message }

    @AfterTest
    fun tearDown() {
        access.release()
        extraAccesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    /** Counts what reaches the real host, so "one dialog" is an observed fact. */
    private class CountingHost(private val real: PermissionRequestHost) : PermissionRequestHost by real {
        var launchCalls: Int = 0
        var shown: Int = 0

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launchCalls++
            return real.launch(androidPermissions, onResult).also { if (it) shown++ }
        }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    private fun controllerOver(
        storage: InMemoryKeyValueStorage,
        over: ActivityAccess,
        host: PermissionRequestHost = ActivityResultPermissionRequestHost(over, logger),
    ): PermissionController = createPermissionController(application, storage, over, host, logger = logger)

    private fun declareCamera() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
    }

    private fun answer(
        activity: Activity,
        request: ShadowActivity.PermissionsRequest,
        granted: Boolean,
        alsoGrantInPlatform: Boolean = granted,
    ) {
        if (alsoGrantInPlatform) shadowOf(application).grantPermissions(camera)
        // The platform clears the "a request is up" flag BEFORE it calls onRequestPermissionsResult, so a
        // dialog launched from inside the answer (a queued request starting) is a fresh request.
        endPlatformRequest(activity)
        val result: Int = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        @Suppress("DEPRECATION") // What the platform calls; ComponentActivity routes it to its registry.
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { result },
        )
        endPlatformRequest(activity)
    }

    /**
     * The platform's own dispatch of a permission answer also clears the activity's "a request is up"
     * flag; calling `onRequestPermissionsResult` by hand does not, and the activity would then answer
     * the next request on it at once with an empty (cancelled) result. Clearing it is what the real
     * answer does, so a second dialog on the same activity can be modelled.
     */
    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun lastRequest(activity: Activity): ShadowActivity.PermissionsRequest =
        assertNotNull(shadowOf(activity).lastRequestedPermission, "no dialog was launched from $activity")

    @Test
    fun `a request pending across a rotation resumes granted on the new instance and the flow ends Granted`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val flow = PermissionRequestFlow(CommonPermission.Camera, controllerOver(InMemoryKeyValueStorage(), access))
            val screen: ActivityController<ComponentActivity> = resumedActivity()

            val start = async { flow.start() }
            assertFalse(start.isCompleted)
            assertEquals(PermissionFlowState.Requesting, flow.state.value)
            val request: ShadowActivity.PermissionsRequest = lastRequest(screen.get())

            screen.recreate()
            assertFalse(start.isCompleted, "the rotation alone ended the request")
            assertEquals(PermissionFlowState.Requesting, flow.state.value, "the flow left Requesting on a rotation")

            answer(screen.get(), request, granted = true)

            assertTrue(start.isCompleted, "the answer delivered to the new instance never reached the request")
            assertEquals(PermissionFlowState.Granted, start.getCompleted())
            assertEquals(PermissionFlowState.Granted, flow.state.value)
        }

    @Test
    fun `ten rotations while a request is pending launch one dialog in total`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val host = CountingHost(ActivityResultPermissionRequestHost(access, logger))
            val controller: PermissionController = controllerOver(InMemoryKeyValueStorage(), access, host)
            val screen: ActivityController<ComponentActivity> = resumedActivity()
            val status = async { controller.request(CommonPermission.Camera) }
            val request: ShadowActivity.PermissionsRequest = lastRequest(screen.get())

            repeat(10) { rotation ->
                screen.recreate()
                assertFalse(status.isCompleted, "rotation $rotation ended the request")
                assertEquals(1, host.shown, "rotation $rotation launched another dialog")
                assertNull(
                    shadowOf(screen.get()).lastRequestedPermission,
                    "rotation $rotation showed a dialog from the new instance",
                )
            }
            assertEquals(1, host.launchCalls)

            answer(screen.get(), request, granted = true)

            assertTrue(status.isCompleted)
            assertEquals(PermissionStatus.Granted, status.getCompleted())
            assertEquals(1, host.launchCalls, "one request must show exactly one dialog")
        }

    @Test
    fun `a caller cancelled with the dialog up leaves the host usable once the stale answer arrives, and the stale answer resolves nothing`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val host = CountingHost(ActivityResultPermissionRequestHost(access, logger))
            val controller: PermissionController = controllerOver(InMemoryKeyValueStorage(), access, host)
            val screen: ActivityController<ComponentActivity> = resumedActivity()

            val first = launch { controller.request(CommonPermission.Camera) }
            val staleRequest: ShadowActivity.PermissionsRequest = lastRequest(screen.get())
            first.cancelAndJoin()

            // The dialog is still on screen. A request queued behind the cancelled one waits for it
            // (it is neither refused nor answered unchanged), launching nothing meanwhile.
            val next = async { controller.request(CommonPermission.Camera) }
            assertFalse(next.isCompleted)
            assertEquals(1, host.launchCalls)
            assertEquals(1, host.shown)

            // The user answers the dialog nobody waits for: the answer is dropped, it decides nothing.
            answer(screen.get(), staleRequest, granted = false)
            assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))

            // The host is free: the queued request is answered by its own dialog only.
            assertFalse(next.isCompleted, "the stale answer resolved the new request: ${logs()} / ${if (next.isCompleted) next.getCompleted() else null} / shown ${host.shown} calls ${host.launchCalls}")
            assertEquals(2, host.shown, "the host stayed unusable after the stale answer")
            answer(screen.get(), lastRequest(screen.get()), granted = true)
            assertTrue(next.isCompleted)
            assertEquals(PermissionStatus.Granted, next.getCompleted())
        }

    @Test
    fun `a caller cancelled with the dialog up and its activity then finished leaves the host usable on the next activity`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val host = CountingHost(ActivityResultPermissionRequestHost(access, logger))
            val controller: PermissionController = controllerOver(InMemoryKeyValueStorage(), access, host)
            val first: ActivityController<ComponentActivity> = resumedActivity()
            val caller = launch { controller.request(CommonPermission.Camera) }
            caller.cancelAndJoin()

            // Queued behind the stale dialog: it waits (neither refused nor answered unchanged).
            val queued = async { controller.request(CommonPermission.Camera) }
            assertFalse(queued.isCompleted)
            assertEquals(1, host.shown)

            first.get().finish()
            first.pause().stop().destroy()
            // The finished activity abandoned the stale dialog, which frees the host; with no activity
            // resumed yet the queued request's launch has nowhere to go and is refused.
            assertEquals(PermissionStatus.NotDetermined, queued.await())
            val second: ActivityController<ComponentActivity> = resumedActivity()
            val next = async { controller.request(CommonPermission.Camera) }

            assertEquals(2, host.shown, "no recovery path after the activity was finished")
            assertFalse(next.isCompleted)
            answer(second.get(), lastRequest(second.get()), granted = true)
            assertTrue(next.isCompleted)
            assertEquals(PermissionStatus.Granted, next.getCompleted())
        }

    @Test
    fun `process death with a request pending leaves a new controller over the same storage reading never asked, and it can ask`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val storage = InMemoryKeyValueStorage()
            val dying: ActivityController<ComponentActivity> = resumedActivity()
            val before: PermissionController = controllerOver(storage, access)
            val pendingRequest = async { before.request(CommonPermission.Camera) }
            assertFalse(pendingRequest.isCompleted)

            // The process dies with the dialog up: the activity goes, and so does the old tracker; the
            // old controller and its request are never completed by anyone.
            dying.get().finish()
            dying.pause().stop().destroy()
            access.release()

            val newAccess: ActivityAccess = createActivityAccess(application).also { extraAccesses += it }
            val reborn: ActivityController<ComponentActivity> = resumedActivity()
            val after: PermissionController = controllerOver(storage, newAccess)

            assertEquals(
                PermissionStatus.NotDetermined,
                after.check(CommonPermission.Camera),
                "a dialog that never resolved was read back as a refusal",
            )
            val retry = async { after.request(CommonPermission.Camera) }
            assertFalse(retry.isCompleted)
            answer(reborn.get(), lastRequest(reborn.get()), granted = true)
            assertTrue(retry.isCompleted)
            assertEquals(PermissionStatus.Granted, retry.getCompleted())
        }

    @Test
    fun `process death after a refusal keeps the persisted refusal and a dead pending second dialog does not change it`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declareCamera()
            val packageManager = shadowOf(application.packageManager)
            packageManager.setShouldShowRequestPermissionRationale(camera, true)
            val storage = InMemoryKeyValueStorage()
            val dying: ActivityController<ComponentActivity> = resumedActivity()
            val before: PermissionController = controllerOver(storage, access)

            val first = async { before.request(CommonPermission.Camera) }
            answer(dying.get(), lastRequest(dying.get()), granted = false)
            assertEquals(PermissionStatus.Denied(explainFirst = true), first.getCompleted())

            // A second dialog is up when the process dies.
            val second = async { before.request(CommonPermission.Camera) }
            assertFalse(second.isCompleted, "the second dialog was not shown: ${logs()}")
            dying.get().finish()
            dying.pause().stop().destroy()
            access.release()

            val newAccess: ActivityAccess = createActivityAccess(application).also { extraAccesses += it }
            resumedActivity()
            val after: PermissionController = controllerOver(storage, newAccess)

            assertEquals(PermissionStatus.Denied(explainFirst = true), after.check(CommonPermission.Camera))
            packageManager.setShouldShowRequestPermissionRationale(camera, false)
            assertEquals(
                PermissionStatus.PermanentlyDenied,
                after.check(CommonPermission.Camera),
                "the persisted refusal and the cleared rationale must read as a permanent denial",
            )
        }

    @Test
    fun `twenty finished activities handed over in both orders abandon the pending request exactly once each and keep the host usable`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val host = ActivityResultPermissionRequestHost(access)
            var current: ActivityController<ComponentActivity> = resumedActivity()

            repeat(20) { round ->
                val answers: MutableList<Map<String, Boolean>> = mutableListOf()
                assertTrue(host.launch(listOf(camera)) { answers += it }, "round $round: the host refused a launch")

                current.get().finish()
                val next: ActivityController<ComponentActivity>
                if (round % 2 == 0) {
                    // The finished activity is destroyed before the next one resumes.
                    current.pause().stop().destroy()
                    next = resumedActivity()
                } else {
                    // The next activity resumes first; the finished one is destroyed afterwards.
                    current.pause()
                    next = resumedActivity()
                    current.stop().destroy()
                }

                assertEquals(listOf(emptyMap<String, Boolean>()), answers, "round $round: abandoned zero or several times")
                current = next
            }

            val answers: MutableList<Map<String, Boolean>> = mutableListOf()
            assertTrue(host.launch(listOf(camera)) { answers += it })
            answer(current.get(), lastRequest(current.get()), granted = true)
            assertEquals(listOf(mapOf(camera to true)), answers, "the last request was not delivered exactly once")
        }

    @Test
    fun `twenty rotations in a row each deliver their answer exactly once and abandon nothing`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            shadowOf(application).denyPermissions(camera)
            val host = ActivityResultPermissionRequestHost(access)
            val screen: ActivityController<ComponentActivity> = resumedActivity()

            repeat(20) { round ->
                val answers: MutableList<Map<String, Boolean>> = mutableListOf()
                assertTrue(host.launch(listOf(camera)) { answers += it }, "round $round: the host refused a launch")
                val request: ShadowActivity.PermissionsRequest = lastRequest(screen.get())

                screen.recreate()
                assertEquals(emptyList(), answers, "round $round: a rotation delivered or abandoned the request: ${logs()} finishing=${screen.get().isFinishing}")
                answer(screen.get(), request, granted = round % 2 == 0, alsoGrantInPlatform = false)

                assertEquals(listOf(mapOf(camera to (round % 2 == 0))), answers, "round $round: not delivered exactly once")
            }
        }
}
