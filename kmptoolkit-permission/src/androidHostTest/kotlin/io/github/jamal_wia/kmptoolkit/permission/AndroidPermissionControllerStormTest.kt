package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The Android controller over the real activity tracker and the real host, with callers on real
 * threads, cancellations that land mid-operation, and the resume callbacks of the main thread
 * arriving in between.
 *
 * Every wait is bounded in real time (`withTimeout`, capped loops); nothing advances virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidPermissionControllerStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    private fun newAccess(): ActivityAccess = createActivityAccess(application).also { accesses += it }

    private fun resumed(type: Class<out Activity>): ActivityController<out Activity> =
        Robolectric.buildActivity(type).also { activities += it }.setup()

    /** At most [cap] main-looper tasks; never an open-ended idle() while other threads keep posting. */
    private fun drain(cap: Int = 64) {
        var ran = 0
        while (ran < cap && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    /** Resume listeners served by a real tracker, counting what is subscribed and not yet cancelled. */
    private class TrackerListeners(private val access: ActivityAccess) : ResumeListeners() {
        val live: AtomicInteger = AtomicInteger()
        val maxLive: AtomicInteger = AtomicInteger()

        override fun add(listener: () -> Unit): ActivitySubscription {
            maxLive.accumulateAndGet(live.incrementAndGet()) { a, b -> maxOf(a, b) }
            val real: ActivitySubscription = access.addOnActivityResumedListener { listener() }
            val once = AtomicInteger()
            return object : ActivitySubscription {
                override fun cancel() {
                    real.cancel()
                    if (once.getAndIncrement() == 0) live.decrementAndGet()
                }
            }
        }
    }

    private fun declare(vararg permissions: String) {
        permissions.forEach { shadowOf(application).denyPermissions(it) }
        application.declareInManifest(*permissions)
    }

    private fun answer(activity: Activity, request: ShadowActivity.PermissionsRequest, permission: String, granted: Boolean) {
        if (granted) shadowOf(application).grantPermissions(permission)
        val result: Int = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        // The platform's own dispatch clears the activity's "a request is up" flag BEFORE it calls
        // onRequestPermissionsResult, so a dialog launched from inside the answer is a fresh request.
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
        @Suppress("DEPRECATION") // What the platform calls; ComponentActivity routes it to its registry.
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { result },
        )
    }

    private fun lastRequest(activity: Activity): ShadowActivity.PermissionsRequest =
        assertNotNull(shadowOf(activity).lastRequestedPermission, "no dialog was launched from $activity")

    private class CountingHost(private val real: PermissionRequestHost) : PermissionRequestHost by real {
        var shown: Int = 0

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean =
            real.launch(androidPermissions, onResult).also { if (it) shown++ }
    }

    @Test
    fun `a queued caller that starts inline from the first caller's answer gets its own dialog and its own answer`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            declare(camera, microphone)
            val access: ActivityAccess = newAccess()
            val host = CountingHost(ActivityResultPermissionRequestHost(access))
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, host)
            val screen = resumed(ComponentActivity::class.java)

            val first: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
            val firstDialog: ShadowActivity.PermissionsRequest = lastRequest(screen.get())
            val second: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Microphone) }
            assertFalse(second.isCompleted)

            // The answer is delivered by the registry; with an unconfined dispatcher the first caller, and then
            // the queued one, run inside that delivery, so the second dialog is launched from within it.
            answer(screen.get(), firstDialog, camera, granted = true)

            assertTrue(first.isCompleted)
            assertEquals(PermissionStatus.Granted, first.getCompleted())
            assertEquals(2, host.shown, "the queued caller's dialog was not shown from inside the first answer")
            assertFalse(second.isCompleted, "the queued caller was answered before its own dialog was")
            answer(screen.get(), lastRequest(screen.get()), microphone, granted = true)
            assertTrue(second.isCompleted)
            assertEquals(PermissionStatus.Granted, second.getCompleted())
        }

    @Test
    fun `a second special access caller that starts inline inside the first one's resume delivery waits for its own return`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
            val access: ActivityAccess = newAccess()
            val screen = resumed(Activity::class.java)
            val listeners = TrackerListeners(access)
            val opened = AtomicInteger()
            val controller: AndroidPermissionController = testController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                sdkInt = Build.VERSION.SDK_INT,
                resumeListeners = listeners,
                launchScreen = { opened.incrementAndGet(); true },
            )

            val first: Deferred<PermissionStatus> = async { controller.request(AndroidPermission.InstallPackages) }
            val second: Deferred<PermissionStatus> = async { controller.request(AndroidPermission.InstallPackages) }
            assertEquals(1, opened.get())

            screen.pause().resume() // the first return: the second caller starts inside this very delivery

            assertTrue(first.isCompleted, "the first return did not end the first wait")
            assertEquals(2, opened.get(), "the second screen was not opened")
            assertFalse(second.isCompleted, "the first caller's resume ended the second caller's wait")
            assertEquals(1, listeners.live.get())

            screen.pause().resume()
            assertTrue(second.isCompleted, "the second return did not end the second wait")
            assertEquals(0, listeners.live.get(), "listeners were left subscribed")
        }

    @Test
    fun `observe collectors started and cancelled on other threads while the main thread resumes leave no listener on the real tracker`() = runBlocking {
        val access: ActivityAccess = newAccess()
        val screen = resumed(Activity::class.java)
        val listeners = TrackerListeners(access)
        val controller: AndroidPermissionController =
            testController(context = application, storage = InMemoryKeyValueStorage(), resumeListeners = listeners)
        val live: ArrayDeque<Job> = ArrayDeque()
        val deadline: Long = System.nanoTime() + 20_000_000_000L

        var round = 0
        while (round < 150 && System.nanoTime() < deadline) {
            live.addLast(launch(Dispatchers.Default) { controller.observe(CommonPermission.Camera).collect { } })
            screen.pause().resume() // the real tracker delivers on this (main) thread
            drain()
            if (live.size > 6) withTimeout(5_000) { live.removeFirst().cancelAndJoin() }
            round++
        }
        live.forEach { withTimeout(5_000) { it.cancelAndJoin() } }
        drain()

        assertTrue(round >= 100, "only $round rounds ran")
        assertEquals(0, listeners.live.get(), "listeners are left subscribed on the real tracker")
    }

    @Test
    fun `forty special access callers on other threads, a third of them cancelled at random, are served one at a time and leave no listener`() = runBlocking {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        val access: ActivityAccess = newAccess()
        val screen = resumed(Activity::class.java)
        val listeners = TrackerListeners(access)
        val opened = AtomicInteger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            sdkInt = Build.VERSION.SDK_INT,
            resumeListeners = listeners,
            launchScreen = { opened.incrementAndGet(); true },
        )
        val random = Random(7)
        val callers: List<Deferred<PermissionStatus>> = List(40) {
            async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { controller.request(AndroidPermission.InstallPackages) }
        }
        val cancellers: List<Job> = callers.filter { random.nextInt(3) == 0 }.map { caller ->
            launch(Dispatchers.Default) {
                delay(random.nextLong(0, 40))
                caller.cancel()
            }
        }

        val deadline: Long = System.nanoTime() + 30_000_000_000L
        while (callers.any { !it.isCompleted } && System.nanoTime() < deadline) {
            drain()
            screen.pause().resume() // the user coming back from Settings, again and again
            Thread.sleep(1)
        }
        cancellers.forEach { withTimeout(5_000) { it.join() } }
        drain()

        val stalled: List<Int> = callers.indices.filter { !callers[it].isCompleted }
        assertTrue(stalled.isEmpty(), "callers $stalled never finished (opened ${opened.get()})")
        assertTrue(listeners.maxLive.get() <= 1, "${listeners.maxLive.get()} requests waited for a resume at once")
        assertEquals(0, listeners.live.get(), "listeners are left subscribed")
        assertTrue(opened.get() <= 40)
    }
}
