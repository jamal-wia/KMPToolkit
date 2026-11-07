package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The Android controller under churn that the one-call-at-a-time tests do not apply: the real
 * activity tracker's resume callbacks while a special-access screen is open, many callers behind one
 * dialog queue, and `observe` collectors that come and go with the screen.
 *
 * Waits are bounded: either the work is driven synchronously on the test (main) thread with an
 * unconfined dispatcher, or every wait on another thread is a `withTimeout` in real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidPermissionControllerAdversarialTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    /** The tracked activity class: only its resumes can end a wait, as `isTracked` would say in an app. */
    private class MainScreen : Activity()

    private fun resumed(type: Class<out Activity>): ActivityController<out Activity> =
        Robolectric.buildActivity(type).also { activities += it }.setup()

    /** Resume listeners served by a real tracker, counting what is subscribed and not yet cancelled. */
    private class TrackerListeners(private val access: ActivityAccess) : ResumeListeners() {
        val live: AtomicInteger = AtomicInteger()
        val added: AtomicInteger = AtomicInteger()

        override fun add(listener: () -> Unit): ActivitySubscription {
            added.incrementAndGet()
            live.incrementAndGet()
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

    private fun specialAccessController(listeners: ResumeListeners, opened: AtomicInteger): AndroidPermissionController {
        shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
        return testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            sdkInt = Build.VERSION.SDK_INT,
            resumeListeners = listeners,
            launchScreen = { opened.incrementAndGet(); true },
        )
    }

    @Test
    fun `five background and foreground rounds of an untracked activity do not end the special access wait, the tracked return does, and no listener is left`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val access: ActivityAccess = createActivityAccess(application) { it is MainScreen }.also { accesses += it }
            val tracked = resumed(MainScreen::class.java)
            val other = resumed(Activity::class.java)
            tracked.pause() // the picker covers the app's own screen, as Android does
            val listeners = TrackerListeners(access)
            val opened = AtomicInteger()
            val controller: AndroidPermissionController = specialAccessController(listeners, opened)

            val request: Deferred<PermissionStatus> = async { controller.request(AndroidPermission.InstallPackages) }
            assertEquals(1, opened.get())
            assertFalse(request.isCompleted)

            repeat(5) { round ->
                other.pause().stop()
                other.start().resume()
                assertFalse(request.isCompleted, "round $round: an untracked activity's resume ended the wait")
            }
            tracked.stop() // the user is in Settings
            looper.idle() // anything posted by the tracker runs now
            assertFalse(request.isCompleted, "the wait ended before the tracked activity came back")

            other.pause()
            tracked.start().resume() // the real return

            assertTrue(request.isCompleted, "the return of the tracked activity did not end the wait")
            assertTrue(request.getCompleted() is PermissionStatus.Denied)
            assertEquals(0, listeners.live.get(), "listeners were left subscribed")
        }

    @Test
    fun `a special access request made off the main thread ends on the real return only across the same churn and leaves no listener`() = runBlocking {
        val access: ActivityAccess = createActivityAccess(application) { it is MainScreen }.also { accesses += it }
        val tracked = resumed(MainScreen::class.java)
        val other = resumed(Activity::class.java)
        val listeners = TrackerListeners(access)
        val opened = AtomicInteger()
        val controller: AndroidPermissionController = specialAccessController(listeners, opened)

        val request: Deferred<PermissionStatus> =
            async(Dispatchers.Default) { controller.request(AndroidPermission.InstallPackages) }
        // The open is posted to main: nothing happens until the test lets the looper run.
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        while (looper.isIdle && System.nanoTime() < deadline) Thread.sleep(5)
        looper.idle()
        assertEquals(1, opened.get(), "the screen was not opened by the posted work")

        tracked.pause() // Settings covers the app
        repeat(5) { round ->
            other.pause().stop()
            other.start().resume()
            looper.idle()
            assertNull(withTimeoutOrNull(100) { request.await() }, "round $round: an untracked activity's resume ended the wait")
        }
        looper.idle()
        assertNull(withTimeoutOrNull(300) { request.await() }, "the wait ended before the tracked activity came back")

        other.pause()
        tracked.start().resume() // the real return
        val status: PermissionStatus? = withTimeoutOrNull(5_000) { request.await() }

        assertTrue(status is PermissionStatus.Denied, "the real return did not end the wait: $status")
        looper.idle()
        assertEquals(0, listeners.live.get(), "listeners were left subscribed")
    }

    /** A host that never answers by itself: each launch waits in [queue] until the test answers it. */
    private class QueueingHost : PermissionRequestHost {
        val queue: ArrayDeque<Pair<List<String>, (Map<String, Boolean>) -> Unit>> = ArrayDeque()
        val launched: MutableList<String> = mutableListOf()
        var active: Int = 0
        var maxActive: Int = 0

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            active++
            maxActive = maxOf(maxActive, active)
            launched += androidPermissions
            queue.addLast(androidPermissions to onResult)
            return true
        }

        override fun launchForResult(intent: android.content.Intent, onResult: (resultCode: Int) -> Unit): Boolean = false

        fun answerNext(granted: Boolean) {
            val (permissions, onResult) = queue.removeFirst()
            active--
            onResult(permissions.associateWith { granted })
        }
    }

    @Test
    fun `thirty two callers on other threads are served one dialog at a time, a queued caller can be cancelled, and nobody is lost`() = runBlocking {
        val host = QueueingHost()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
        )
        val permissions: List<AndroidPermission.Runtime> = List(32) { AndroidPermission.Runtime("com.example.permission.P$it") }

        val callers: List<Deferred<PermissionStatus>> =
            permissions.map { permission -> async(Dispatchers.Default) { controller.request(permission) } }

        // Main looper work is what launches dialogs; wait for the first one to be up.
        fun pumpUntil(what: String, condition: () -> Boolean) {
            val deadline: Long = System.nanoTime() + 10_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                looper.idle()
                Thread.sleep(2)
            }
            assertTrue(condition(), "timed out waiting for: $what (launched ${host.launched.size}, queue ${host.queue.size})")
        }
        pumpUntil("the first dialog") { host.queue.isNotEmpty() }
        val first: String = host.launched.single()
        val victimIndex: Int = permissions.indexOfLast { it.name != first }
        val victim: Deferred<PermissionStatus> = callers[victimIndex]
        withTimeout(5_000) { victim.cancelAndJoin() }

        var answered = 0
        val deadline: Long = System.nanoTime() + 15_000_000_000L
        while (callers.any { !it.isCompleted } && System.nanoTime() < deadline) {
            looper.idle()
            if (host.queue.isNotEmpty()) {
                assertEquals(1, host.active, "more than one dialog is up at once")
                host.answerNext(granted = false)
                answered++
            } else {
                Thread.sleep(2)
            }
        }

        val stalled: List<Int> = callers.indices.filter { !callers[it].isCompleted }
        assertTrue(stalled.isEmpty(), "callers $stalled never finished after $answered answers")
        assertEquals(1, host.maxActive, "dialogs overlapped")
        assertTrue(victim.isCancelled, "the cancelled caller was not cancelled")
        assertFalse(permissions[victimIndex].name in host.launched, "a dialog was shown for the cancelled caller")
        assertEquals(31, host.launched.size, "a request was lost or shown twice: ${host.launched.size} dialogs for 31 callers")
        assertEquals(31, host.launched.toSet().size)
        callers.filterIndexed { index, _ -> index != victimIndex }.forEach { assertNotNull(it.getCompleted()) }
    }

    @Test
    fun `two hundred observe collectors started and cancelled across resumes leave no resume listener behind`() =
        runTest(UnconfinedTestDispatcher(), timeout = 30.seconds) {
            val listeners = ResumeListeners()
            val controller: AndroidPermissionController = testController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                resumeListeners = listeners,
            )
            val live: ArrayDeque<Job> = ArrayDeque()

            repeat(200) { round ->
                live.addLast(launch { controller.observe(CommonPermission.Camera).collect { } })
                listeners.fire() // an activity resumes while collectors come and go
                if (live.size > 5) live.removeFirst().cancelAndJoin()
                assertEquals(live.size, listeners.count, "round $round: the listener count does not follow the live collectors")
            }
            live.forEach { it.cancelAndJoin() }

            assertEquals(0, listeners.count, "resume listeners are left behind")
            listeners.fire()
            assertTrue(live.all { it.isCancelled })
        }
}
