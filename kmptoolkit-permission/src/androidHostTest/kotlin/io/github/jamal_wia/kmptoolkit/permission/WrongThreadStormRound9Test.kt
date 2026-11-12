package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Round 9 (E1, bm): `check`, `openSettings`, `observe` and `request` called from six threads that are not the main
 * one over the real tracker and the real host, while the main thread pauses and resumes the activity, with collectors
 * that time out and collectors that throw. Nothing may throw, no resume subscription may be left, and the controller
 * must serve a request afterwards.
 */
@RunWith(AndroidJUnit4::class)
class WrongThreadStormRound9Test {

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

    private class CountingAccess(private val inner: ActivityAccess) : ActivityAccess by inner {
        val live: AtomicInteger = AtomicInteger()

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            live.incrementAndGet()
            val real: ActivitySubscription = inner.addOnActivityResumedListener(listener)
            val once = AtomicInteger()
            return object : ActivitySubscription {
                override fun cancel() {
                    real.cancel()
                    if (once.getAndIncrement() == 0) live.decrementAndGet()
                }
            }
        }
    }

    private fun drain(cap: Int = 64) {
        var ran = 0
        while (ran < cap && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    @Test
    fun `six off main threads reading opening and observing while the activity cycles leave nothing subscribed`() = runBlocking {
        withTimeout(120_000) {
            shadowOf(application).grantPermissions(camera)
            shadowOf(application).denyPermissions(microphone)
            application.declareInManifest(camera, microphone)
            val access = CountingAccess(createActivityAccess(application).also { accesses += it })
            val host = ActivityResultPermissionRequestHost(access)
            val controller: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), access, host)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val failures: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue()
            val permissions: List<Permission> = CommonPermission.entries +
                listOf(AndroidPermission.Overlay, AndroidPermission.InstallPackages, AndroidPermission.ExactAlarm)

            val workers: List<Job> = List(6) { worker ->
                launch(Dispatchers.Default) {
                    val rnd = Random(77 + worker)
                    repeat(100) {
                        val permission: Permission = permissions.random(rnd)
                        try {
                            when (rnd.nextInt(6)) {
                                0 -> controller.check(permission)
                                1 -> controller.openSettings(permission)
                                2 -> withTimeoutOrNull(rnd.nextLong(1, 8)) { controller.observe(permission).collect { } }
                                3 -> runCatching {
                                    controller.observe(permission).collect { throw IllegalStateException("collector failed") }
                                }.onFailure { if (it !is IllegalStateException) throw it }
                                4 -> controller.request(CommonPermission.Camera)
                                else -> controller.check(CommonPermission.Microphone)
                            }
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: Throwable) {
                            failures += cause
                        }
                    }
                }
            }
            var cycles = 0
            while (workers.any { it.isActive }) {
                drain()
                if (cycles++ % 3 == 0) screen.pause().resume()
                Thread.sleep(2)
            }
            drain(256)

            assertTrue(failures.isEmpty(), "an off-main call threw: ${failures.firstOrNull()}")
            assertEquals(0, access.live.get(), "resume subscriptions left behind")
            assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
            assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
        }
    }
}
