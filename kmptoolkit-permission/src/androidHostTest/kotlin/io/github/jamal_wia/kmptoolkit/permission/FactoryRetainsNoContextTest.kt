package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * `createPermissionController(context, ...)`: "only its application context is retained". Handed an
 * activity, the controller must not keep that activity alive after it is gone, however much it was used.
 */
@RunWith(AndroidJUnit4::class)
class FactoryRetainsNoContextTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private var controller: PermissionController? = null

    @AfterTest
    fun tearDown() {
        access.release()
    }

    private fun usedThenDestroyedActivity(): WeakReference<Activity> {
        val screen: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val created: PermissionController = createPermissionController(screen.get(), InMemoryKeyValueStorage(), access)
        controller = created
        created.openSettings(CommonPermission.Camera)
        val reference: WeakReference<Activity> = WeakReference(screen.get())
        screen.get().finish()
        screen.pause().stop().destroy()
        screen.close()
        return reference
    }

    @Test
    fun `a controller built from an activity does not keep that activity alive`() = runTest {
        val reference: WeakReference<Activity> = usedThenDestroyedActivity()
        assertNotNull(controller)
        controller?.check(CommonPermission.Camera)

        var cleared = false
        repeat(30) {
            if (!cleared) {
                System.gc()
                System.runFinalization()
                cleared = reference.get() == null
                if (!cleared) Thread.sleep(25)
            }
        }

        assertTrue(cleared, "the controller still holds the activity it was built from")
    }
}
