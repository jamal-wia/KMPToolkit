package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * 04-api-reference as a test. Every `data object X // "ID"` line of the two Android-visible hierarchies must name a
 * type that exists with exactly that id, and every public entry must be on a line; the status table's three
 * extension properties; a consumer's 4.0.0-shaped calls (positional and named) of the Android factory.
 */
@RunWith(AndroidJUnit4::class)
class ApiReferenceIdsAndStatusDocsRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun doc(name: String): String {
        val project: File = File(System.getProperty("user.dir")).absoluteFile
        val root: File = generateSequence(project) { it.parentFile }.first { File(File(it, "docs"), "kmptoolkit-permission").isDirectory }
        return File(File(File(root, "docs"), "kmptoolkit-permission"), name).readText()
    }

    /** `data object Name // "ID"` lines between the opening of [block] and the first closing brace at its indent. */
    private fun documentedIds(block: String): Map<String, String> {
        val text: String = doc("04-api-reference.md")
        val start: Int = text.indexOf(block)
        assertTrue(start >= 0, "$block is not in the API reference")
        val end: Int = text.indexOf("\n```", start)
        val line = Regex("""(?:data object|data class)\s+(\w+).*?//\s*(?:id\s*)?"([^"]+)"""")
        return line.findAll(text.substring(start, end)).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every documented common id belongs to an entry that has it and every entry is documented`() {
        val documented: Map<String, String> = documentedIds("public sealed interface CommonPermission")
        val real: Map<String, String> = CommonPermission.entries.associate { it::class.simpleName!! to it.id }

        assertEquals(real, documented)
        assertEquals(15, CommonPermission.entries.size)
        assertEquals(CommonPermission.entries.size, CommonPermission.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `every documented Android id belongs to an entry that has it and every parameterless entry is documented`() {
        val documented: Map<String, String> = documentedIds("public sealed interface AndroidPermission")
        val component = ComponentName("com.example", "com.example.Thing")
        val real: Map<String, String> = (
            AndroidPermission.specialAccesses + AndroidPermission.NearbyWifiDevices + AndroidPermission.LocalNetwork +
                AndroidPermission.BodySensors + AndroidPermission.BodySensorsBackground
            ).associate { it::class.simpleName!! to it.id }

        for ((name, id) in real) assertEquals(id, documented[name], name)
        assertEquals("ACCESSIBILITY_SERVICE:com.example/com.example.Thing", AndroidPermission.AccessibilityService(component).id)
        assertEquals("DEVICE_ADMIN:com.example/com.example.Thing", AndroidPermission.DeviceAdmin(component).id)
        assertEquals("android.permission.READ_PHONE_STATE", AndroidPermission.Runtime("android.permission.READ_PHONE_STATE").id)
        assertEquals("android.app.role.HOME", AndroidPermission.Role.Home.id)
        assertEquals(setOf("ACCESSIBILITY_SERVICE:<component>", "DEVICE_ADMIN:<component>"), documented.filterKeys { it in setOf("AccessibilityService", "DeviceAdmin") }.values.toSet())
        assertEquals(12, AndroidPermission.specialAccesses.size)
    }

    @Test
    fun `the status properties are the three the table describes and the table's cases are all of them`() {
        val all: List<PermissionStatus> = listOf(
            PermissionStatus.Granted, PermissionStatus.Limited, PermissionStatus.Denied(true), PermissionStatus.Denied(false),
            PermissionStatus.PermanentlyDenied, PermissionStatus.NotDetermined, PermissionStatus.Restricted, PermissionStatus.Unavailable,
        )
        assertEquals(listOf(PermissionStatus.Granted), all.filter { it.isGranted })
        assertEquals(listOf(PermissionStatus.Granted, PermissionStatus.Limited), all.filter { it.isUsable })
        assertEquals(
            listOf(PermissionStatus.Denied(true), PermissionStatus.Denied(false), PermissionStatus.NotDetermined),
            all.filter { it.canPrompt },
        )
        assertEquals(PermissionStatus.Denied(explainFirst = false), PermissionStatus.Denied())
        // Exhaustive without an else: a new case would be a compile error here (and a breaking change for consumers).
        for (status in all) {
            val label: String = when (status) {
                is PermissionStatus.Granted -> "g"
                is PermissionStatus.Limited -> "l"
                is PermissionStatus.Denied -> "d"
                is PermissionStatus.PermanentlyDenied -> "p"
                is PermissionStatus.NotDetermined -> "n"
                is PermissionStatus.Restricted -> "r"
                is PermissionStatus.Unavailable -> "u"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    @Test
    fun `the Android factory takes its arguments positionally and by name as 4_0_0 consumers wrote them`() {
        val storage = InMemoryKeyValueStorage()
        val access: ActivityAccess = io.github.jamal_wia.kmptoolkit.activity.createActivityAccess(app)
        val host: PermissionRequestHost = ActivityResultPermissionRequestHost(access)

        val positional: PermissionController = createPermissionController(
            app, storage, access, host, SystemScreenLauncher.callerTask(access), AndroidPermissionConfig(), NoopLogger,
        )
        val named: PermissionController = createPermissionController(
            context = app, storage = storage, systemScreenLauncher = SystemScreenLauncher.SeparateTask,
            config = AndroidPermissionConfig(keyPrefix = "mine"), logger = NoopLogger,
        )
        val minimal: PermissionController = createPermissionController(app, storage)
        access.release()

        assertEquals(3, listOf(positional, named, minimal).toSet().size)
        assertEquals("mine", AndroidPermissionConfig(keyPrefix = "mine").copy().keyPrefix)
        assertEquals(PermissionScreen(AndroidPermission.Overlay), PermissionScreen(AndroidPermission.Overlay))
    }
}
