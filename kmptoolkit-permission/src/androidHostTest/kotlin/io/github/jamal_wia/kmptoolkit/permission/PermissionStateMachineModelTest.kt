package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A seeded random driver over the real [AndroidPermissionController] against a reference model of the
 * documented contract (03-guide "Denied once versus permanently denied", 05-platform-notes "The stored
 * flags"): the platform refuses a dialog after the second refusal (Android 11+) or after "Don't ask
 * again" (before it); a dismissal changes nothing; a request returns the status the dialog's answer
 * produced; with no activity nothing permanent is decided. The sequence mixes requests, batches, checks,
 * grants and revocations made in Settings, process death and re-creation of the controller over the same
 * storage, the app going to the background and coming back, a caller cancelled with its dialog up whose
 * answer arrives later, and two callers at once. The seed and the step are in every failure message.
 */
@RunWith(AndroidJUnit4::class)
class PermissionStateMachineModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val strings: List<String> = listOf(camera, microphone)

    private enum class Act { ALLOW, DENY, DENY_NEVER, DISMISS }

    /** What Android does to one string, independent of the library. */
    private class Platform(val sdk: Int) {
        val granted: MutableSet<String> = mutableSetOf()
        val refusals: MutableMap<String, Int> = mutableMapOf()
        val never: MutableSet<String> = mutableSetOf()

        fun blocked(s: String): Boolean =
            s !in granted && if (sdk >= 30) (refusals[s] ?: 0) >= 2 else s in never

        fun rationale(s: String): Boolean = s !in granted && (refusals[s] ?: 0) >= 1 && !blocked(s)

        /** The user's answer to a dialog for [group]; what Android reports back for every string of it. */
        fun dialog(group: List<String>, act: Act): Map<String, Boolean> {
            val shown: List<String> = group.filter { it !in granted && !blocked(it) }
            when (act) {
                Act.ALLOW -> granted += shown
                Act.DENY -> shown.forEach { refusals[it] = (refusals[it] ?: 0) + 1 }
                Act.DENY_NEVER -> shown.forEach {
                    if (sdk < 30 && (refusals[it] ?: 0) >= 1) never += it
                    refusals[it] = (refusals[it] ?: 0) + 1
                }
                Act.DISMISS -> Unit
            }
            return group.associateWith { it in granted }
        }
    }

    /** The reference: the documented behaviour, written from the contract and not from the controller. */
    private class Model(sdk: Int) {
        val platform: Platform = Platform(sdk)
        val asked: MutableSet<String> = mutableSetOf()
        val seen: MutableSet<String> = mutableSetOf()
        var background: Boolean = false

        fun status(s: String): PermissionStatus {
            if (s in platform.granted) {
                asked -= s
                seen -= s
                return PermissionStatus.Granted
            }
            if (background) return if (s in asked) PermissionStatus.Denied(explainFirst = false) else PermissionStatus.NotDetermined
            if (platform.rationale(s)) {
                seen += s
                return PermissionStatus.Denied(explainFirst = true)
            }
            return when {
                s !in asked -> PermissionStatus.NotDetermined
                s in seen -> PermissionStatus.PermanentlyDenied
                else -> PermissionStatus.NotDetermined
            }
        }

        private fun canShow(st: PermissionStatus): Boolean = st is PermissionStatus.NotDetermined || st is PermissionStatus.Denied

        private fun afterDialog(s: String): PermissionStatus {
            if (s in platform.granted) {
                asked -= s
                seen -= s
                return PermissionStatus.Granted
            }
            asked += s
            return status(s)
        }

        fun request(s: String, act: Act): PermissionStatus {
            val cur: PermissionStatus = status(s)
            if (!canShow(cur) || background) return cur
            platform.dialog(listOf(s), act)
            return afterDialog(s)
        }

        fun batch(list: List<String>, act: Act): Map<String, PermissionStatus> {
            val candidates: List<String> = list.filter { canShow(status(it)) }
            val results: MutableMap<String, PermissionStatus> = linkedMapOf()
            if (candidates.size >= 2) {
                if (background) {
                    candidates.forEach { results[it] = status(it) }
                } else {
                    platform.dialog(candidates, act)
                    candidates.forEach { results[it] = afterDialog(it) }
                }
            }
            for (s in list) if (s !in results) results[s] = request(s, act)
            return list.associateWith { results.getValue(it) }
        }

        /** A request that is cancelled with its dialog up and answered later: nothing is recorded. */
        fun cancelledThenAnswered(s: String, act: Act) {
            val cur: PermissionStatus = status(s)
            if (!canShow(cur) || background) return
            // A blocked permission shows no dialog, so there is nothing to cancel: the request just ends.
            if (platform.blocked(s)) {
                platform.dialog(listOf(s), act)
                afterDialog(s)
                return
            }
            platform.dialog(listOf(s), act)
        }
    }

    /** A host whose dialog stays up until the driver answers it. */
    private class SimHost(val platform: Platform) : PermissionRequestHost {
        var background: Boolean = false
        var pendingGroup: List<String>? = null
        private var pendingCallback: ((Map<String, Boolean>) -> Unit)? = null
        var launches: Int = 0

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            if (background || pendingGroup != null) return false
            launches++
            val shown: List<String> = androidPermissions.filter { it !in platform.granted && !platform.blocked(it) }
            if (shown.isEmpty()) {
                onResult(androidPermissions.associateWith { it in platform.granted })
                return true
            }
            pendingGroup = androidPermissions
            pendingCallback = onResult
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false

        fun answer(act: Act) {
            val group: List<String> = pendingGroup ?: error("no dialog is up")
            val callback: (Map<String, Boolean>) -> Unit = pendingCallback!!
            pendingGroup = null
            pendingCallback = null
            callback(platform.dialog(group, act))
        }
    }

    private fun CommonPermission.string(): String = if (this == CommonPermission.Camera) camera else microphone

    @Test
    fun `a seeded random sequence of requests and checks agrees with the reference model on every API level boundary`() {
        for (sdk in listOf(29, 30, 33, 35)) {
            for (seed in 1..24) runSequence(sdk, seed.toLong() * 7919 + sdk)
        }
    }

    private fun runSequence(sdk: Int, seed: Long) = runTest(UnconfinedTestDispatcher()) {
        val random = Random(seed)
        val tag = "sdk=$sdk seed=$seed"
        application.declareInManifest(camera, microphone)
        shadowOf(application).denyPermissions(camera, microphone)
        val model = Model(sdk)
        val platform = Platform(sdk)
        val host = SimHost(platform)
        val storage: KeyValueStorage = InMemoryKeyValueStorage()
        fun newController(): AndroidPermissionController = testController(
            context = application,
            storage = storage,
            host = host,
            sdkInt = sdk,
            shouldShowRationale = { s -> if (host.background) null else platform.rationale(s) },
            awaitLaunchable = { !host.background },
        )
        var controller: AndroidPermissionController = newController()
        val perms: List<CommonPermission> = listOf(CommonPermission.Camera, CommonPermission.Microphone)
        val log: MutableList<String> = mutableListOf()

        fun sync() {
            // The robolectric grants mirror the simulated platform, the way checkSelfPermission reads it.
            for (s in strings) {
                if (s in platform.granted) shadowOf(application).grantPermissions(s) else shadowOf(application).denyPermissions(s)
            }
        }

        fun randomAct(): Act = Act.entries[random.nextInt(Act.entries.size)]

        suspend fun drain(jobs: List<Deferred<*>>, actFor: (List<String>) -> Act) {
            var guard = 0
            while (jobs.any { !it.isCompleted }) {
                check(++guard < 60) { "$tag: callers never finished; log=$log" }
                if (host.pendingGroup != null) {
                    host.answer(actFor(host.pendingGroup!!))
                    sync()
                } else {
                    kotlinx.coroutines.yield()
                }
            }
        }

        repeat(45) { step ->
            val where = "$tag step=$step"
            sync()
            when (random.nextInt(11)) {
                0, 1, 2 -> {
                    val p: CommonPermission = perms[random.nextInt(2)]
                    val act: Act = randomAct()
                    log += "request ${p.string().substringAfterLast('.')} $act"
                    val expected: PermissionStatus = model.request(p.string(), act)
                    val call: Deferred<PermissionStatus> = async { controller.request(p) }
                    drain(listOf(call)) { act }
                    assertEquals(expected, call.await(), "$where request mismatch; log=$log")
                }

                3 -> {
                    val order: List<CommonPermission> = if (random.nextBoolean()) perms else perms.reversed()
                    val act: Act = randomAct()
                    log += "batch ${order.map { it.string().substringAfterLast('.') }} $act"
                    // each dialog the batch needs takes the next scripted act; the model consumes them the same way
                    val expected: Map<String, PermissionStatus> = model.batch(order.map { it.string() }, act)
                    val call: Deferred<Map<Permission, PermissionStatus>> = async { controller.request(order) }
                    drain(listOf(call)) { act }
                    val got: Map<Permission, PermissionStatus> = call.await()
                    assertEquals(order, got.keys.toList(), "$where batch keys; log=$log")
                    for (p in order) assertEquals(expected.getValue(p.string()), got.getValue(p), "$where batch ${p.id}; log=$log")
                }

                4, 5 -> {
                    val p: CommonPermission = perms[random.nextInt(2)]
                    log += "check ${p.string().substringAfterLast('.')}"
                    assertEquals(model.status(p.string()), controller.check(p), "$where check mismatch; log=$log")
                }

                6 -> {
                    val s: String = strings[random.nextInt(2)]
                    if (random.nextBoolean()) {
                        log += "settings grant $s"
                        platform.granted += s
                        model.platform.granted += s
                    } else {
                        log += "settings revoke $s"
                        platform.granted -= s
                        model.platform.granted -= s
                    }
                    sync()
                }

                7 -> {
                    log += "process death"
                    controller = newController()
                }

                8 -> {
                    host.background = !host.background
                    model.background = host.background
                    log += "background=${host.background}"
                }

                9 -> {
                    val p: CommonPermission = perms[random.nextInt(2)]
                    val act: Act = randomAct()
                    log += "cancelled ${p.string().substringAfterLast('.')} then answered $act"
                    model.cancelledThenAnswered(p.string(), act)
                    val call: Deferred<PermissionStatus> = async { controller.request(p) }
                    if (host.pendingGroup != null) {
                        call.cancel()
                        host.answer(act)
                        sync()
                    } else {
                        drain(listOf(call)) { Act.DENY }
                    }
                }

                else -> {
                    val a: CommonPermission = perms[random.nextInt(2)]
                    val b: CommonPermission = perms[random.nextInt(2)]
                    val actA: Act = randomAct()
                    val actB: Act = if (a == b) actA else randomAct()
                    log += "two callers ${a.id}/$actA ${b.id}/$actB"
                    val expectedA: PermissionStatus = model.request(a.string(), actA)
                    val expectedB: PermissionStatus = model.request(b.string(), actB)
                    val callA: Deferred<PermissionStatus> = async { controller.request(a) }
                    val callB: Deferred<PermissionStatus> = async { controller.request(b) }
                    drain(listOf(callA, callB)) { group -> if (group.first() == a.string()) actA else actB }
                    assertEquals(expectedA, callA.await(), "$where first caller; log=$log")
                    assertEquals(expectedB, callB.await(), "$where second caller; log=$log; platform granted=${platform.granted} refusals=${platform.refusals} modelAsked=${model.asked} seen=${model.seen} launches=${host.launches}")
                }
            }
            // The simulated worlds stay the same, and the controller agrees with the model about both strings.
            assertEquals(model.platform.granted, platform.granted, "$where: worlds diverged (harness); log=$log")
            assertEquals(model.platform.refusals.filterValues { it > 0 }, platform.refusals.filterValues { it > 0 }, "$where: refusals diverged; log=$log")
            for (p in perms) {
                assertEquals(model.status(p.string()), controller.check(p), "$where: status of ${p.id} after the step; log=$log")
            }
            assertTrue(host.pendingGroup == null, "$where: a dialog is left up; log=$log")
        }
    }
}
