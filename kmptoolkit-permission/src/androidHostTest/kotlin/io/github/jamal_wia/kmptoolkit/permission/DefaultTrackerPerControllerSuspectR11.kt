package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Round 11 suspect (the shape of kmptoolkit-systembars' SharedActivityAccess, E2 R18-S1 family): every
 * `createPermissionController(context, storage)` that takes the default `activityAccess` registers a NEW
 * `ActivityLifecycleCallbacks` on the Application, and the controller has no `release()` and hands out no
 * handle, so the consumer can never remove it. N controllers leave N callbacks on the Application for the
 * life of the process, each called on every activity lifecycle event.
 */
@RunWith(AndroidJUnit4::class)
class DefaultTrackerPerControllerSuspectR11 {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun lifecycleCallbacks(): Int {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        val list = field.get(application) as Collection<*>
        return synchronized(list) { list.size }
    }

    @Test
    fun `five hundred controllers built with the default activity access leave at most one lifecycle callback on the application`() {
        val before: Int = lifecycleCallbacks()
        repeat(500) { createPermissionController(application, InMemoryKeyValueStorage()) }
        val added: Int = lifecycleCallbacks() - before
        assertTrue(added <= 1, "500 default-built controllers registered $added lifecycle callbacks on the Application that nothing can unregister")
    }
}
