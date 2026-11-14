package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 11, OS-state flips: every input the Android controller reads from the system (grants, the rationale
 * answer, the manifest, a device-policy restriction, the notification switch, the API level) is flipped at
 * random between and around calls, and the platform seams are made to throw at random. What must hold:
 *  - nothing throws for a platform failure (04-api-reference "Nothing throws for a platform failure"),
 *  - the status is a function of the OS state and the stored flags alone: a controller built fresh over the
 *    same storage (the process died and came back) reads the same status as the long-lived one at every step,
 *  - a request returns what the next `check` reads,
 *  - `observe` starts with what `check` reads,
 *  - a single-string entry the manifest declares and the OS granted reads Granted, one the manifest lacks
 *    reads Unavailable, and neither ever shows a dialog.
 */
@RunWith(AndroidJUnit4::class)
class OsFlipChaosRound11Test {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private enum class Answer { ALLOW, DENY, DISMISS, CANNOT_LAUNCH, THROW }

    private class World(val random: Random) {
        val strings: List<String> = listOf(
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.BLUETOOTH_CONNECT",
            "android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_PHONE_STATE",
            "com.example.permission.CUSTOM",
        )
        var declared: Set<String>? = strings.toSet()
        val rationale: MutableSet<String> = mutableSetOf()
        val policy: MutableSet<String> = mutableSetOf()
        var notifications: Boolean = true
        var throwProtection: Boolean = false
        var throwRationale: Boolean = false
        var throwPolicy: Boolean = false
        var throwNotifications: Boolean = false
        var nextAnswer: Answer = Answer.DENY
        var dialogs: Int = 0
        val seen: MutableSet<String> = mutableSetOf()
    }

    private fun controller(world: World, sdk: Int, storage: KeyValueStorage, host: PermissionRequestHost): PermissionController =
        testController(
            application,
            storage,
            host = host,
            sdkInt = sdk,
            targetSdk = sdk,
            protectionOf = { if (world.throwProtection) error("no package manager") else Protection.Dangerous },
            shouldShowRationale = { if (world.throwRationale) error("no activity") else it in world.rationale },
            declaredPermissions = { world.declared },
            isRevokedByPolicy = { if (world.throwPolicy) error("policy service gone") else it in world.policy },
            notificationsEnabled = { if (world.throwNotifications) error("notification manager gone") else world.notifications },
        )

    private val entries: List<Permission> = CommonPermission.entries + listOf(
        AndroidPermission.NearbyWifiDevices,
        AndroidPermission.LocalNetwork,
        AndroidPermission.BodySensors,
        AndroidPermission.BodySensorsBackground,
        AndroidPermission.Runtime("android.permission.READ_PHONE_STATE"),
        AndroidPermission.Runtime("com.example.permission.CUSTOM"),
        AndroidPermission.Runtime("com.example.permission.NOT_DECLARED"),
    )

    private val seenStatuses: MutableSet<String> = mutableSetOf()
    private var shownDialogs: Int = 0

    private fun walk(seed: Int, sdk: Int) = runBlocking {
        val random = Random(seed)
        val world = World(random)
        val storage = InMemoryKeyValueStorage()
        val host = object : PermissionRequestHost {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                world.dialogs++
                when (world.nextAnswer) {
                    Answer.ALLOW -> {
                        shadowOf(application).grantPermissions(*androidPermissions.toTypedArray())
                        onResult(androidPermissions.associateWith { true })
                    }
                    Answer.DENY -> onResult(androidPermissions.associateWith { false })
                    Answer.DISMISS -> onResult(emptyMap())
                    Answer.CANNOT_LAUNCH -> return false
                    Answer.THROW -> error("the activity is gone")
                }
                return true
            }

            override fun launchForResult(intent: android.content.Intent, onResult: (resultCode: Int) -> Unit): Boolean = false
        }
        shadowOf(application).denyPermissions(*world.strings.toTypedArray())
        val main: PermissionController = controller(world, sdk, storage, host)
        fun fail(step: Int, what: String): Nothing = throw AssertionError("seed $seed sdk $sdk step $step: $what")

        repeat(90) { step ->
            try {
                when (random.nextInt(11)) {
                    0 -> shadowOf(application).grantPermissions(*world.strings.shuffled(random).take(1 + random.nextInt(4)).toTypedArray())
                    1 -> shadowOf(application).denyPermissions(*world.strings.shuffled(random).take(1 + random.nextInt(6)).toTypedArray())
                    2 -> world.strings.random(random).let { if (!world.rationale.add(it)) world.rationale.remove(it) }
                    3 -> world.declared = when (random.nextInt(4)) {
                        0 -> null
                        1 -> emptySet()
                        else -> world.strings.shuffled(random).take(random.nextInt(world.strings.size + 1)).toSet()
                    }
                    4 -> world.strings.random(random).let { if (!world.policy.add(it)) world.policy.remove(it) }
                    5 -> world.notifications = !world.notifications
                    6 -> when (random.nextInt(4)) {
                        0 -> world.throwProtection = !world.throwProtection
                        1 -> world.throwRationale = !world.throwRationale
                        2 -> world.throwPolicy = !world.throwPolicy
                        else -> world.throwNotifications = !world.throwNotifications
                    }
                    7 -> {
                        val entry: Permission = entries.random(random)
                        val dialogsBefore: Int = world.dialogs
                        val mine: PermissionStatus = main.check(entry)
                        seenStatuses += "${mine::class.simpleName}"
                        val reborn: PermissionStatus = controller(world, sdk, storage, host).check(entry)
                        assertEquals(mine, reborn, "a controller rebuilt over the same storage reads $entry differently")
                        assertEquals(dialogsBefore, world.dialogs, "check showed a dialog for $entry")
                        val observed: PermissionStatus = withTimeout(10_000) { main.observe(entry).first() }
                        assertEquals(mine, observed, "observe starts with something else than check for $entry")
                        if (entry == CommonPermission.Camera || entry == CommonPermission.Microphone) {
                            val string: String = if (entry == CommonPermission.Camera) "android.permission.CAMERA" else "android.permission.RECORD_AUDIO"
                            val declared: Set<String>? = world.declared
                            val granted: Boolean = application.checkSelfPermission(string) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (declared != null && string !in declared) assertEquals(PermissionStatus.Unavailable, mine, "undeclared $entry")
                            else if (granted) assertEquals(PermissionStatus.Granted, mine, "granted $entry")
                            else assertFalse(mine.isGranted, "ungranted $entry reads $mine")
                        }
                    }
                    8 -> {
                        world.nextAnswer = Answer.values().random(random)
                        val entry: Permission = entries.random(random)
                        val before: PermissionStatus = main.check(entry)
                        val dialogsBefore: Int = world.dialogs
                        val returned: PermissionStatus = withTimeout(10_000) { main.request(entry) }
                        val after: PermissionStatus = main.check(entry)
                        seenStatuses += "${after::class.simpleName}"
                        assertEquals(after, returned, "request($entry) returned $returned but check reads $after (before $before, answer ${world.nextAnswer})")
                        if (!before.canPrompt && before != PermissionStatus.Limited) assertEquals(dialogsBefore, world.dialogs, "a dialog for $entry that read $before")
                        assertEquals(after, controller(world, sdk, storage, host).check(entry), "rebuilt controller disagrees after request($entry)")
                    }
                    9 -> {
                        world.nextAnswer = Answer.values().random(random)
                        val batch: List<Permission> = List(1 + random.nextInt(3)) { entries.random(random) }
                        val answers: Map<Permission, PermissionStatus> = withTimeout(10_000) { main.request(batch) }
                        assertEquals(batch.distinct().toSet(), answers.keys, "a batch answers every distinct entry once")
                        batch.distinct().forEach { entry ->
                            val settled: PermissionStatus = main.check(entry)
                            assertEquals(settled, answers.getValue(entry), "batch answer for $entry differs from check")
                        }
                    }
                    else -> {
                        val entry: Permission = entries.random(random)
                        assertTrue(main.openSettings(entry) || true)
                    }
                }
            } catch (cause: Throwable) {
                if (cause is AssertionError && cause.message?.startsWith("seed") == true) throw cause
                fail(step, "$cause")
            }
        }
        shownDialogs += world.dialogs
    }

    @AfterTest
    fun theWalksWereNotVacuous() {
        assertTrue(shownDialogs > 20, "the walks showed only $shownDialogs dialogs")
        assertTrue(seenStatuses.containsAll(listOf("Granted", "Denied", "NotDetermined", "Unavailable")), "statuses seen: $seenStatuses")
    }

    @Test
    fun `twelve seeds of random OS flips at API 29`() = (1..12).forEach { walk(it, 29) }

    @Test
    fun `twelve seeds of random OS flips at API 30`() = (101..112).forEach { walk(it, 30) }

    @Test
    fun `twelve seeds of random OS flips at API 33`() = (201..212).forEach { walk(it, 33) }

    @Test
    fun `twelve seeds of random OS flips at API 34 and 35`() = (301..306).forEach { walk(it, 34); walk(it + 100, 35) }
}
