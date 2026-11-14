package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A device with no device policy service (some embedded builds) cannot have an active admin: a declared receiver reads as
 * not active, never as active. Found by flipping the fallback: no test built a context without the service.
 */
@RunWith(AndroidJUnit4::class)
class ResultGrantsNoPolicyServiceRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName("io.github.jamal_wia.kmptoolkit.permission.test", "com.example.AdminReceiver")

    @Test
    fun `a declared admin receiver with no policy service reads as not active`() {
        shadowOf(application.packageManager).addOrUpdateReceiver(
            ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
            },
        )
        val noService: Context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.DEVICE_POLICY_SERVICE) null else super.getSystemService(name)
        }

        assertEquals(false, ResultGrants(noService, NoopLogger).isActive(AndroidPermission.DeviceAdmin(component)))
    }
}
