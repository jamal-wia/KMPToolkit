package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The API-level windows of the "Special accesses" table of 05-platform-notes at both ends of the supported range
 * (24 and 35): below the level that introduced an access, "an access the OS used to give every app is Granted; one that
 * did not exist is Unavailable", and "a request returns the status at once without calling the launcher, and
 * `openSettings` returns `false`" for the six that have nothing to open there. Nothing is stored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpecialAccessWindowsRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The level the code under test reads (`Build.VERSION.SDK_INT`), set on the one default image: an image per level is the memory cost round 8 hit. */
    private var sdk: Int = android.os.Build.VERSION.SDK_INT

    private fun everyLevel(block: () -> Unit) {
        val real: Int = android.os.Build.VERSION.SDK_INT
        try {
            for (level in 24..35) {
                org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", level)
                sdk = level
                block()
            }
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", real)
        }
    }

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    /** Access to the first API level where it exists (the table's "Android API" column). */
    private val introducedAt: Map<AndroidPermission.SpecialAccess, Int> = mapOf(
        AndroidPermission.ExactAlarm to 31,
        AndroidPermission.AllFilesAccess to 30,
        AndroidPermission.MediaManagement to 31,
        AndroidPermission.InstallPackages to 26,
        AndroidPermission.FullScreenNotifications to 34,
        AndroidPermission.KeepPermissionsWhenUnused to 30,
    )

    private fun controller(declared: Set<String>, screens: MutableList<SystemScreenRequest>): PermissionController =
        testController(
            app, InMemoryKeyValueStorage(), sdkInt = sdk, declaredPermissions = { declared },
            launchScreen = { request -> screens += request; true },
        )

    @Test
    fun `below its API level an access the OS gave every app is Granted and one that did not exist is Unavailable`() = everyLevel {
        val granted: List<AndroidPermission.SpecialAccess> = listOf(
            AndroidPermission.ExactAlarm, AndroidPermission.AllFilesAccess, AndroidPermission.KeepPermissionsWhenUnused,
        )
        val unavailable: List<AndroidPermission.SpecialAccess> = listOf(AndroidPermission.MediaManagement, AndroidPermission.InstallPackages)
        val c = controller(emptySet(), mutableListOf())
        for (access in granted) {
            val expected: Boolean = sdk < introducedAt.getValue(access)
            if (expected) assertEquals(PermissionStatus.Granted, bounded { c.check(access) }, "$access on API $sdk")
        }
        for (access in unavailable) {
            if (sdk < introducedAt.getValue(access)) {
                assertEquals(PermissionStatus.Unavailable, bounded { c.check(access) }, "$access on API $sdk")
            } else {
                assertTrue(bounded { c.check(access) } != PermissionStatus.Unavailable, "$access exists on API $sdk")
            }
        }
    }

    @Test
    fun `full-screen notifications below API 34 are Granted when declared and Unavailable when not`() = everyLevel {
        if (sdk >= 34) return@everyLevel
        assertEquals(PermissionStatus.Granted, bounded { controller(setOf(Manifest.permission.USE_FULL_SCREEN_INTENT), mutableListOf()).check(AndroidPermission.FullScreenNotifications) })
        assertEquals(PermissionStatus.Unavailable, bounded { controller(emptySet(), mutableListOf()).check(AndroidPermission.FullScreenNotifications) })
    }

    @Test
    fun `an access with nothing to open here answers at once without the launcher and openSettings says false`() = everyLevel {
        for ((access, from) in introducedAt) {
            val screens: MutableList<SystemScreenRequest> = mutableListOf()
            val declared: Set<String> = setOf(Manifest.permission.USE_FULL_SCREEN_INTENT)
            val c = controller(declared, screens)
            val before: PermissionStatus = bounded { c.check(access) }

            // Where a screen opens, request waits for the user to come back: only the nothing-to-open case is asked.
            val answer: PermissionStatus? = if (sdk < from) bounded { c.request(access) } else null
            val opened: Boolean = c.openSettings(access)

            if (sdk < from) {
                assertEquals(before, answer, "$access on API $sdk")
                assertEquals(emptyList(), screens, "$access on API $sdk: the launcher was called")
                assertFalse(opened, "$access on API $sdk")
            } else if (before != PermissionStatus.Granted) {
                assertEquals(true, opened, "$access on API $sdk")
            }
        }
    }

    @Test
    fun `an access that exists here and is not granted reads Denied with an explanation and nothing is stored`() = everyLevel {
        val storage = InMemoryKeyValueStorage()
        val c = testController(app, storage, sdkInt = sdk, declaredPermissions = { emptySet() })
        for (access in listOf(AndroidPermission.Overlay, AndroidPermission.WriteSettings, AndroidPermission.UsageStatsAccess, AndroidPermission.DoNotDisturbAccess)) {
            val status: PermissionStatus = bounded { c.check(access) }
            assertTrue(status == PermissionStatus.Denied(explainFirst = true) || status == PermissionStatus.Granted, "$access read $status on API $sdk")
        }
        assertEquals(emptyMap(), storage.contents)
    }
}
