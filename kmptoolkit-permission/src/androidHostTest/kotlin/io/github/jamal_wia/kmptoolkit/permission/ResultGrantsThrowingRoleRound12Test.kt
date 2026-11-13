package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.app.role.RoleManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * "Nothing throws for a platform failure": a role manager whose held-check throws reads as not held (the side that never claims a
 * grant the system did not report), not as held. Found by flipping the fallback of that catch: no test saw it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], shadows = [ResultGrantsThrowingRoleRound12Test.ThrowingRoleManager::class])
class ResultGrantsThrowingRoleRound12Test {

    @Implements(RoleManager::class)
    class ThrowingRoleManager {
        @Implementation
        fun isRoleAvailable(roleName: String): Boolean = true

        @Implementation
        fun isRoleHeld(roleName: String): Boolean = throw IllegalStateException("the role service died")
    }

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a held check that throws reads as not held`() {
        val grants = ResultGrants(application, NoopLogger)

        assertEquals(false, grants.isHeld(AndroidPermission.Role.Home))
    }
}
