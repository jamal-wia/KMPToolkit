package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * A long-horizon seeded model of [FakePermissionController] with what the existing model test leaves out: held requests
 * (`holdRequests`), the request queue with several callers at once (single requests and batches), a caller cancelled while
 * queued, while its dialog is up and after the hold is cleared, the answer a cancelled request keeps and applies when the hold
 * clears, `openSettings` with `settingsAvailable` flipped, and an observer on every permission for the whole run. The model is
 * written from 06-testing ("Requests are serialized", "A cancelled request keeps the user's answer") and not from the code;
 * thousands of steps per seed, many seeds, the seed and step in every failure message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLongHorizonHeldModelRound12Test {

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

    private enum class CallState { QUEUED, UP, DONE, CANCELLED }

    private class ModelCall(val id: Int, val list: List<Permission>, val single: Boolean) {
        var index: Int = 0
        var state: CallState = CallState.QUEUED
        var sawHold: Boolean = false
        val answers: MutableMap<Permission, PermissionStatus> = LinkedHashMap()
    }

    private inner class Model(var default: PermissionStatus) {
        val statuses: MutableMap<Permission, PermissionStatus> = mutableMapOf()
        val scripts: MutableMap<Permission, PermissionStatus> = mutableMapOf()
        var holding: Boolean = false
        val abandoned: MutableList<Permission> = mutableListOf()
        var owner: ModelCall? = null
        val waiters: ArrayDeque<ModelCall> = ArrayDeque()
        val emitted: Map<Permission, MutableList<PermissionStatus>> = permissions.associateWith { mutableListOf() }

        fun statusOf(p: Permission): PermissionStatus = statuses[p] ?: default

        fun changed() {
            for (p: Permission in permissions) if (emitted.getValue(p).lastOrNull() != statusOf(p)) emitted.getValue(p) += statusOf(p)
        }

        fun shows(p: Permission, cur: PermissionStatus): Boolean =
            cur is PermissionStatus.NotDetermined || cur is PermissionStatus.Denied || (cur is PermissionStatus.Limited && p in scripts)

        fun settle(p: Permission): PermissionStatus {
            val cur: PermissionStatus = statusOf(p)
            if (!shows(p, cur)) return cur
            val outcome: PermissionStatus = scripts[p] ?: PermissionStatus.Granted
            statuses[p] = outcome
            changed()
            return outcome
        }

        fun advance(c: ModelCall) {
            c.state = CallState.UP
            while (c.index < c.list.size) {
                val p: Permission = c.list[c.index]
                val cur: PermissionStatus = statusOf(p)
                if (!shows(p, cur)) {
                    c.answers[p] = cur
                } else {
                    if (holding) {
                        c.sawHold = true
                        return
                    }
                    c.answers[p] = settle(p)
                }
                c.index++
            }
            c.state = CallState.DONE
            owner = null
            nextWaiter()
        }

        fun nextWaiter() {
            val next: ModelCall = waiters.removeFirstOrNull() ?: return
            owner = next
            advance(next)
        }

        fun start(c: ModelCall) {
            if (owner == null) {
                owner = c
                advance(c)
            } else {
                waiters += c
            }
        }

        fun cancel(c: ModelCall) {
            when (c.state) {
                CallState.DONE, CallState.CANCELLED -> return
                CallState.QUEUED -> waiters.remove(c)
                CallState.UP -> {
                    val p: Permission = c.list[c.index]
                    if (holding) abandoned += p else settle(p)
                    owner = null
                }
            }
            c.state = CallState.CANCELLED
            if (owner == null) nextWaiter()
        }

        fun setHold(on: Boolean) {
            if (!on) {
                val pending: List<Permission> = abandoned.toList()
                abandoned.clear()
                pending.forEach { settle(it) }
            }
            holding = on
            if (!on) owner?.let { if (it.state == CallState.UP) advance(it) }
        }
    }

    @Test
    fun `thousands of seeded steps with held requests queued callers and cancels agree with the documented contract`() {
        for (seed in 1..SEEDS) run(seed.toLong() * 15485863L)
        // Non-vacuity: the run really exercised the interesting paths.
        assertTrue(cancelledWhileUp > 100, "cancelled while the dialog was up: $cancelledWhileUp")
        assertTrue(cancelledWhileQueued > 100, "cancelled while queued: $cancelledWhileQueued")
        assertTrue(abandonedStored > 50, "answers kept for cancelled requests: $abandonedStored")
        assertTrue(batchesDone > 200, "batches finished: $batchesDone")
        assertTrue(heldThenReleased > 100, "requests finished after a hold: $heldThenReleased")
    }

    private var cancelledWhileUp: Int = 0
    private var cancelledWhileQueued: Int = 0
    private var abandonedStored: Int = 0
    private var batchesDone: Int = 0
    private var heldThenReleased: Int = 0

    private fun run(seed: Long) = runTest(UnconfinedTestDispatcher()) {
        val random = Random(seed)
        val fake = FakePermissionController(defaultStatus = all[random.nextInt(all.size)])
        val model = Model(fake.defaultStatus)
        val calls: MutableList<Pair<ModelCall, Deferred<Any?>>> = mutableListOf()
        val recordedChecks: MutableList<Permission> = mutableListOf()
        val recordedRequests: MutableList<Permission> = mutableListOf()
        val recordedBatches: MutableList<List<Permission>> = mutableListOf()
        val recordedSettings: MutableList<Permission> = mutableListOf()
        val emitted: Map<Permission, MutableList<PermissionStatus>> = permissions.associateWith { mutableListOf() }
        var settingsAvailable = true
        var nextId = 0
        val counted: MutableSet<Int> = mutableSetOf()

        model.changed()
        for (p: Permission in permissions) {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fake.observe(p).collect { emitted.getValue(p) += it } }
        }

        suspend fun flush() {
            repeat(3) { yield() }
            testScheduler.runCurrent()
        }

        repeat(STEPS) { step: Int ->
            val where = "seed=$seed step=$step"
            val p: Permission = permissions[random.nextInt(permissions.size)]
            when (random.nextInt(14)) {
                0 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.setStatus(p, s)
                    model.statuses[p] = s
                    model.changed()
                }
                1 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.defaultStatus = s
                    model.default = s
                    model.changed()
                }
                2 -> {
                    val s: PermissionStatus = all[random.nextInt(all.size)]
                    fake.scriptRequest(p, s)
                    model.scripts[p] = s
                }
                3 -> {
                    recordedChecks += p
                    assertEquals(model.statusOf(p), fake.check(p), "$where check")
                }
                4, 5, 6 -> {
                    val call = ModelCall(nextId++, listOf(p), single = true)
                    recordedRequests += p
                    model.start(call)
                    calls += call to async { fake.request(p) }
                }
                7, 8 -> {
                    val list: List<Permission> = List(random.nextInt(0, 5)) { permissions[random.nextInt(permissions.size)] }
                    val call = ModelCall(nextId++, list.distinct(), single = false)
                    recordedBatches += list
                    model.start(call)
                    calls += call to async { fake.request(list) }
                }
                9 -> {
                    if (!model.holding) {
                        fake.holdRequests = true
                        model.setHold(true)
                    }
                }
                10 -> {
                    fake.holdRequests = false
                    model.setHold(false)
                }
                11 -> {
                    val live: List<Pair<ModelCall, Deferred<Any?>>> = calls.filter { it.first.state == CallState.QUEUED || it.first.state == CallState.UP }
                    if (live.isNotEmpty()) {
                        val (call: ModelCall, job: Deferred<Any?>) = live[random.nextInt(live.size)]
                        if (call.state == CallState.UP) cancelledWhileUp++ else cancelledWhileQueued++
                        if (call.state == CallState.UP && model.holding) abandonedStored++
                        job.cancel()
                        model.cancel(call)
                    }
                }
                12 -> {
                    recordedSettings += p
                    assertEquals(settingsAvailable, fake.openSettings(p), "$where openSettings")
                }
                else -> {
                    settingsAvailable = !settingsAvailable
                    fake.settingsAvailable = settingsAvailable
                }
            }
            flush()
            for ((call: ModelCall, _) in calls) {
                if (call.state == CallState.DONE && counted.add(call.id)) {
                    if (!call.single && call.list.size >= 2) batchesDone++
                    if (call.sawHold) heldThenReleased++
                }
            }
            // Every call is in the state the model says; a finished one carries the model's answer.
            for ((call: ModelCall, job: Deferred<Any?>) in calls) {
                when (call.state) {
                    CallState.DONE -> {
                        assertTrue(job.isCompleted && !job.isCancelled, "$where call ${call.id} should be done (${call.list.map { it.id }})")
                        val got: Any? = job.await()
                        if (call.single) {
                            assertEquals(call.answers.getValue(call.list.single()), got, "$where call ${call.id} single answer")
                        } else {
                            assertEquals(call.answers.toMap(), got, "$where call ${call.id} batch answers")
                            assertEquals(call.answers.keys.toList(), (got as Map<*, *>).keys.toList(), "$where call ${call.id} batch order")
                        }
                    }
                    CallState.CANCELLED -> assertTrue(job.isCancelled, "$where call ${call.id} should be cancelled")
                    CallState.QUEUED, CallState.UP -> assertFalse(job.isCompleted, "$where call ${call.id} finished early (${call.state}, hold=${model.holding})")
                }
            }
            for (q: Permission in permissions) {
                assertEquals(model.statusOf(q), fake.check(q).also { recordedChecks += q }, "$where status of ${q.id}")
                assertEquals(model.emitted.getValue(q), emitted.getValue(q), "$where observer of ${q.id}")
            }
            assertEquals(model.holding, fake.holdRequests, "$where hold flag")
            assertEquals(recordedChecks, fake.checks, "$where recorded checks")
            assertEquals(recordedRequests, fake.requests, "$where recorded requests")
            assertEquals(recordedBatches, fake.batchRequests, "$where recorded batches")
            assertEquals(recordedSettings, fake.openedSettings, "$where recorded settings")
        }
        // Whatever is still up or queued ends when the hold clears and the callers are cancelled; nothing is left stuck.
        fake.holdRequests = false
        model.setHold(false)
        flush()
        for ((call: ModelCall, job: Deferred<Any?>) in calls) {
            if (call.state == CallState.DONE) assertTrue(job.isCompleted, "seed=$seed final: call ${call.id} should be done")
        }
        assertEquals(calls.count { it.first.state == CallState.DONE }, calls.count { it.second.isCompleted && !it.second.isCancelled }, "seed=$seed final completed calls")
    }

    private companion object {
        const val SEEDS: Int = 30
        const val STEPS: Int = 1000
    }
}
