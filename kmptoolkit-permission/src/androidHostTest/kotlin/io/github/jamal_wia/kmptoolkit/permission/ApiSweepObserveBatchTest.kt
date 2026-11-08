package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Every API level from 24 to 37, random batches, a platform that grants and refuses at random and a manifest
 * that declares a random part of what the catalog asks for. Two promises written from the contract, checked
 * after every call:
 *
 * - `request` puts no Android permission to the user twice in one call ("every runtime permission that can
 *   still prompt shares one system dialog") and the status it returns is the status `check` reads next;
 * - `observe` "re-reads after every request through the same controller that covered the permission (or the
 *   entry it stands for on this API level)": whatever a request changed, every observer of a catalog entry
 *   ends on what `check` reads, without any resume in between.
 *
 * Seeded and capped: 14 levels x 10 cases, at most 4 calls each.
 */
@RunWith(AndroidJUnit4::class)
class ApiSweepObserveBatchTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private val entries: List<Permission> = CommonPermission.entries + listOf(
        AndroidPermission.NearbyWifiDevices,
        AndroidPermission.LocalNetwork,
        AndroidPermission.BodySensors,
        AndroidPermission.BodySensorsBackground,
    )

    private fun stringsAt(sdk: Int): Set<String> = entries
        .mapNotNull { runtimeRule(it, sdk, 37) { Protection.Dangerous } as? RuntimeRule.Dialog }
        .flatMap { it.androidPermissions }
        .toSet()

    @Test
    fun `batches and observers stay consistent on every API level`() {
        for (sdk in 24..37) for (iteration in 0 until 10) {
            val seed: Long = sdk * 1000L + iteration
            runCase(sdk, iteration, Random(seed), "sdk=$sdk case=$iteration")
        }
    }

    private fun runCase(sdk: Int, iteration: Int, random: Random, label: String) = runTest(UnconfinedTestDispatcher()) {
        val universe: List<String> = (stringsAt(sdk) + stringsAt(sdk - 1)).toList().sorted()
        val declared: Set<String> = universe.filter { random.nextInt(100) < 85 }.toSet()
        val granted: MutableSet<String> = universe.filter { random.nextInt(100) < 15 }.toMutableSet()
        shadowOf(application).denyPermissions(*universe.toTypedArray())
        if (granted.isNotEmpty()) shadowOf(application).grantPermissions(*granted.toTypedArray())
        val refusals: MutableMap<String, Int> = mutableMapOf()
        val groups: MutableList<List<String>> = mutableListOf()
        val host = object : PermissionRequestHost {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                groups += androidPermissions
                val answer: Map<String, Boolean> = androidPermissions.associateWith { string ->
                    val permanent: Boolean = (refusals[string] ?: 0) >= 2
                    val yes: Boolean = string in granted || (!permanent && random.nextInt(100) < 40)
                    if (yes) {
                        granted += string
                        shadowOf(application).grantPermissions(string)
                    } else {
                        refusals[string] = (refusals[string] ?: 0) + 1
                    }
                    yes
                }
                onResult(answer)
                return true
            }

            override fun launchForResult(intent: android.content.Intent, onResult: (resultCode: Int) -> Unit): Boolean = false
        }
        val notificationsOn: Boolean = random.nextBoolean()
        // An app that opted into API 37 behaviour makes the local network a runtime permission on API 37.
        val target: Int = if (random.nextBoolean()) 35 else 37
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            host = host,
            sdkInt = sdk,
            targetSdk = target,
            shouldShowRationale = { string -> if (string in granted) false else (refusals[string] ?: 0) == 1 },
            declaredPermissions = { declared },
            notificationsEnabled = { notificationsOn },
        )
        val last: MutableMap<Permission, PermissionStatus> = mutableMapOf()
        val collections: List<Job> = entries.map { entry ->
            launch(start = CoroutineStart.UNDISPATCHED) { controller.observe(entry).collect { last[entry] = it } }
        }

        repeat(random.nextInt(1, 5)) { call ->
            groups.clear()
            val batch: List<Permission> = List(random.nextInt(1, 6)) { entries.random(random) }
            val returned: Map<Permission, PermissionStatus> = if (batch.size == 1 && random.nextBoolean()) {
                mapOf(batch.single() to controller.request(batch.single()))
            } else {
                controller.request(batch)
            }
            val where = "$label call=$call batch=$batch groups=$groups"
            val strings: List<String> = groups.flatten()
            assertEquals(strings.distinct(), strings, "$where: one Android permission was put to the user twice in one call")
            for ((entry, status) in returned) {
                assertEquals(controller.check(entry), status, "$where: request returned another status for $entry than check reads next")
            }
            for (entry in entries) {
                val status: PermissionStatus = controller.check(entry)
                assertEquals(status, last[entry], "$where: the observer of $entry is behind check after the request")
                // The platform's own grant always wins over anything remembered.
                var source: Permission = entry
                var rule: RuntimeRule? = runtimeRule(source, sdk, target) { Protection.Dangerous }
                while (rule is RuntimeRule.SameAs) {
                    source = rule.permission
                    rule = runtimeRule(source, sdk, target) { Protection.Dangerous }
                }
                when (rule) {
                    is RuntimeRule.Dialog -> {
                        val mine: List<String> = rule.androidPermissions.filter { it in declared }
                        if (mine.isEmpty()) {
                            assertEquals(PermissionStatus.Unavailable, status, "$where: $entry asks for nothing the manifest declares")
                        } else {
                            when (rule.grantOf(mine.associateWith { it in granted })) {
                                Grant.Full -> assertEquals(PermissionStatus.Granted, status, "$where: $entry")
                                Grant.Limited -> assertEquals(PermissionStatus.Limited, status, "$where: $entry")
                                Grant.None -> assertTrue(
                                    status != PermissionStatus.Granted && status != PermissionStatus.Limited,
                                    "$where: $entry reads $status although nothing it asks for is granted",
                                )
                            }
                        }
                    }

                    RuntimeRule.NoGrantNeeded -> assertEquals(PermissionStatus.Granted, status, "$where: $entry")
                    else -> Unit
                }
            }
        }
        collections.forEach { it.cancel() }
    }
}
