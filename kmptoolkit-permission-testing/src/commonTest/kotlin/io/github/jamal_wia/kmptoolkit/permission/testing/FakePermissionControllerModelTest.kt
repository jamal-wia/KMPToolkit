package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A seeded random driver over [FakePermissionController] against a model written from its documented
 * contract (06-testing): an unscripted permission reads the default, a request from NotDetermined or
 * Denied (and from Limited only when an outcome is scripted) resolves to the scripted outcome or to
 * Granted and the next check agrees, every other status comes back unchanged, a batch answers every
 * distinct permission in order, recordings keep the order of the calls and `observe` emits the status
 * on collection and then only a different one. The seed and step are in every failure message.
 */
class FakePermissionControllerModelTest {

    private val permissions: List<Permission> = listOf(CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Location)
    private val all: List<PermissionStatus> = listOf(
        PermissionStatus.Granted,
        PermissionStatus.Limited,
        PermissionStatus.Denied(explainFirst = true),
        PermissionStatus.Denied(explainFirst = false),
        PermissionStatus.PermanentlyDenied,
        PermissionStatus.NotDetermined,
        PermissionStatus.Restricted,
        PermissionStatus.Unavailable,
    )

    @Test
    fun `random sequences agree with the documented contract and observers emit only changes`() {
        for (seed in 1..60) run(seed.toLong() * 104729)
    }

    private fun run(seed: Long) = runTest(UnconfinedTestDispatcher()) {
        val random = Random(seed)
        val fake = FakePermissionController(defaultStatus = all[random.nextInt(all.size)])
        var default: PermissionStatus = fake.defaultStatus
        val statuses: MutableMap<Permission, PermissionStatus> = mutableMapOf()
        val scripts: MutableMap<Permission, PermissionStatus> = mutableMapOf()
        fun statusOf(p: Permission): PermissionStatus = statuses[p] ?: default
        val checks: MutableList<Permission> = mutableListOf()
        val requests: MutableList<Permission> = mutableListOf()
        val batches: MutableList<List<Permission>> = mutableListOf()

        val emitted: Map<Permission, MutableList<PermissionStatus>> = permissions.associateWith { mutableListOf() }
        val expectedEmitted: Map<Permission, MutableList<PermissionStatus>> = permissions.associateWith { mutableListOf() }
        // Observers start at different moments, so the model starts each one's list at its collection.
        val observing: MutableSet<Permission> = mutableSetOf()

        fun startObserving(p: Permission) {
            if (!observing.add(p)) return
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fake.observe(p).collect { emitted.getValue(p) += it } }
            expectedEmitted.getValue(p) += statusOf(p)
        }

        fun changed() {
            for (p in observing) if (expectedEmitted.getValue(p).last() != statusOf(p)) expectedEmitted.getValue(p) += statusOf(p)
        }

        fun modelRequest(p: Permission): PermissionStatus {
            val cur: PermissionStatus = statusOf(p)
            val shows: Boolean = cur is PermissionStatus.NotDetermined || cur is PermissionStatus.Denied ||
                (cur is PermissionStatus.Limited && p in scripts)
            if (!shows) return cur
            val outcome: PermissionStatus = scripts[p] ?: PermissionStatus.Granted
            statuses[p] = outcome
            return outcome
        }

        repeat(60) { step ->
            val where = "seed=$seed step=$step"
            val p: Permission = permissions[random.nextInt(permissions.size)]
            when (random.nextInt(9)) {
                0 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.setStatus(p, s)
                    statuses[p] = s
                }
                1 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.defaultStatus = s
                    default = s
                }
                2 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.scriptRequest(p, s)
                    scripts[p] = s
                }
                3 -> {
                    checks += p
                    assertEquals(statusOf(p), fake.check(p), "$where check")
                }
                4, 5 -> {
                    requests += p
                    assertEquals(modelRequest(p), fake.request(p), "$where request")
                    assertEquals(statusOf(p), fake.check(p).also { checks += p }, "$where check after request")
                }
                6 -> {
                    val list: List<Permission> = List(random.nextInt(0, 5)) { permissions[random.nextInt(permissions.size)] }
                    batches += list
                    val expected: Map<Permission, PermissionStatus> = list.distinct().associateWith { modelRequest(it) }
                    val got: Map<Permission, PermissionStatus> = fake.request(list)
                    assertEquals(expected, got, "$where batch")
                    assertEquals(expected.keys.toList(), got.keys.toList(), "$where batch order")
                }
                7 -> startObserving(p)
                else -> {
                    fake.clearRecordings()
                    checks.clear()
                    requests.clear()
                    batches.clear()
                }
            }
            changed()
            assertEquals(checks, fake.checks, "$where recorded checks")
            assertEquals(requests, fake.requests, "$where recorded requests")
            assertEquals(batches, fake.batchRequests, "$where recorded batches")
            for (q in observing) assertEquals(expectedEmitted.getValue(q), emitted.getValue(q), "$where observer of ${q.id}")
        }
    }
}
