package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The platform-read guard on every API level from 24 to 37: with all four reads that can fail failing at
 * once, `check`, `request` and `observe` of every catalog entry still answer, never with a grant and never
 * with a permanent refusal the user did not make ("Nothing throws for a platform failure"), and a read the
 * level does not need is not made at all (the notification switch from API 33 is a permission, not a switch).
 */
@RunWith(AndroidJUnit4::class)
class PlatformReadSweepTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private val entries: List<Permission> = CommonPermission.entries + listOf(
        AndroidPermission.NearbyWifiDevices,
        AndroidPermission.LocalNetwork,
        AndroidPermission.BodySensors,
        AndroidPermission.BodySensorsBackground,
    )

    @Test
    fun `with every failing read failing nothing throws and nothing is concluded the user did not decide`() = runTest {
        for (sdk in 24..37) {
            var switchReads = 0
            val logger = RecordingLogger()
            val host = RecordingHost(answers = { false })
            val controller: AndroidPermissionController = testController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                host = host,
                sdkInt = sdk,
                targetSdk = 35,
                protectionOf = { throw IllegalStateException("protection") },
                shouldShowRationale = { throw IllegalStateException("rationale") },
                isRevokedByPolicy = { throw IllegalStateException("policy") },
                notificationsEnabled = { switchReads++; throw IllegalStateException("switch") },
                logger = logger,
            )
            for (entry in entries) {
                val before: PermissionStatus = controller.check(entry)
                val after: PermissionStatus = controller.request(entry)
                val observed: PermissionStatus = controller.observe(entry).first()
                assertFalse(before is PermissionStatus.Restricted, "API $sdk $entry: a failing policy read must not read as Restricted")
                var rule: RuntimeRule? = runtimeRule(entry, sdk, 35) { Protection.Dangerous }
                while (rule is RuntimeRule.SameAs) rule = runtimeRule(rule.permission, sdk, 35) { Protection.Dangerous }
                if (rule !is RuntimeRule.NoGrantNeeded) {
                    assertTrue(
                        after != PermissionStatus.Granted && after != PermissionStatus.Limited,
                        "API $sdk $entry: nothing was granted, yet $after",
                    )
                }
                if (entry != CommonPermission.Notifications || sdk >= 33) {
                    assertTrue(
                        after !is PermissionStatus.PermanentlyDenied || before is PermissionStatus.PermanentlyDenied,
                        "API $sdk $entry: a refusal the user never made on an unreadable rationale reads PermanentlyDenied",
                    )
                }
                assertEquals(controller.check(entry), observed, "API $sdk $entry: observe disagrees with check")
            }
            if (sdk >= 33) assertEquals(0, switchReads, "API $sdk: the notification switch is read although Notifications is a permission here")
            if (sdk < 33) {
                assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Notifications), "API $sdk: an unreadable switch reads as off")
            }
        }
    }
}
