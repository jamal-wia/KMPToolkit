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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A long-horizon seeded run of the Android controller that asserts invariants instead of a step-by-step model, so it can go for
 * thousands of steps over every API level boundary and mix what no model has to predict: a dialog that stays up while the caller
 * checks, is cancelled, or the process dies and the answer reaches a controller that no longer exists; settings grants and
 * revocations (one-time grants expiring), the app going to the background, controllers rebuilt over the same storage.
 *
 * Invariants after every step: `check` shows no dialog and agrees with the platform (Granted if and only if granted, an
 * explain-first denial only where the platform would show its rationale); a controller rebuilt over the same storage reads the
 * same status (process death loses nothing); a request that was not interrupted returns what the next check reads; a permanent
 * denial survives checks and rebuilds until the platform grants it; the observer of each permission has last emitted what `check`
 * reads. The seed and the step are in every failure message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PermissionInvariantsLongHorizonRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val strings: List<String> = listOf(camera, microphone)
    private val perms: List<CommonPermission> = listOf(CommonPermission.Camera, CommonPermission.Microphone)

    private enum class Act { ALLOW, DENY, DISMISS }

    private class Platform(val sdk: Int, val application: Application) {
        val granted: MutableSet<String> = mutableSetOf()
        val refusals: MutableMap<String, Int> = mutableMapOf()
        val never: MutableSet<String> = mutableSetOf()

        fun blocked(s: String): Boolean = s !in granted && if (sdk >= 30) (refusals[s] ?: 0) >= 2 else s in never

        fun rationale(s: String): Boolean = s !in granted && (refusals[s] ?: 0) >= 1 && !blocked(s)

        fun dialog(group: List<String>, act: Act): Map<String, Boolean> {
            val shown: List<String> = group.filter { it !in granted && !blocked(it) }
            when (act) {
                Act.ALLOW -> {
                    granted += shown
                    // the system's grant is visible the moment the dialog is answered, before any callback runs
                    if (shown.isNotEmpty()) shadowOf(application).grantPermissions(*shown.toTypedArray())
                }
                Act.DENY -> shown.forEach { refusals[it] = (refusals[it] ?: 0) + 1 }
                Act.DISMISS -> Unit
            }
            return group.associateWith { it in granted }
        }
    }

    /** A host that answers at once, or keeps the dialog up until the driver answers it. */
    private class Host(val platform: Platform) : PermissionRequestHost {
        var background: Boolean = false
        var hold: Boolean = false
        var act: Act = Act.DENY
        var launches: Int = 0
        var upGroup: List<String>? = null
        private var upCallback: ((Map<String, Boolean>) -> Unit)? = null

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            if (background || upGroup != null) return false
            launches++
            val shown: List<String> = androidPermissions.filter { it !in platform.granted && !platform.blocked(it) }
            if (hold && shown.isNotEmpty()) {
                upGroup = androidPermissions
                upCallback = onResult
                return true
            }
            onResult(platform.dialog(androidPermissions, act))
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false

        /** The user answers the dialog that is up; the callback may belong to a controller that is gone. */
        fun answer(withAct: Act) {
            val group: List<String> = upGroup ?: return
            val callback: (Map<String, Boolean>) -> Unit = upCallback!!
            upGroup = null
            upCallback = null
            callback(platform.dialog(group, withAct))
        }
    }

    @Test
    fun `thousands of steps over every API level boundary keep the invariants of the controller`() {
        var steps = 0
        for (sdk in listOf(29, 30, 33, 35)) {
            for (seed in 1..SEEDS) steps += run(sdk, seed.toLong() * 104729L + sdk)
        }
        assertTrue(steps >= 4 * SEEDS * STEPS, "ran only $steps steps")
        assertTrue(interrupted > 100, "dialogs interrupted by cancel or death: $interrupted")
        assertTrue(permanentlyDenied > 100, "steps that read a permanent denial: $permanentlyDenied")
        assertTrue(granted > 100, "steps that read a grant: $granted")
        assertTrue(rationaleSeen > 100, "steps that read an explain-first denial: $rationaleSeen")
    }

    private var interrupted: Int = 0
    private var permanentlyDenied: Int = 0
    private var granted: Int = 0
    private var rationaleSeen: Int = 0

    private fun run(sdk: Int, seed: Long): Int {
        var executed = 0
        runTest(UnconfinedTestDispatcher()) {
            val random = Random(seed)
            val tag = "sdk=$sdk seed=$seed"
            application.declareInManifest(camera, microphone)
            shadowOf(application).denyPermissions(camera, microphone)
            val platform = Platform(sdk, application)
            val host = Host(platform)
            val storage: KeyValueStorage = InMemoryKeyValueStorage()
            var listeners = ResumeListeners()
            fun newController(): AndroidPermissionController = testController(
                context = application,
                storage = storage,
                host = host,
                sdkInt = sdk,
                shouldShowRationale = { s -> if (host.background) null else platform.rationale(s) },
                awaitLaunchable = { !host.background },
                resumeListeners = listeners,
            )
            var controller: AndroidPermissionController = newController()
            val observed: Map<CommonPermission, MutableList<PermissionStatus>> = perms.associateWith { mutableListOf() }
            val log: MutableList<String> = mutableListOf()

            fun sync() {
                for (s in strings) {
                    if (s in platform.granted) shadowOf(application).grantPermissions(s) else shadowOf(application).denyPermissions(s)
                }
            }

            fun observeAll(c: AndroidPermissionController) {
                for (p in perms) backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.observe(p).collect { observed.getValue(p) += it } }
            }
            observeAll(controller)

            val sticky: MutableSet<CommonPermission> = mutableSetOf()

            /** The user answers every dialog that comes up until [jobs] are done; a job that cannot finish is a hang. */
            suspend fun settle(where: String, vararg jobs: Deferred<*>) {
                var guard = 0
                while (jobs.any { !it.isCompleted }) {
                    check(++guard < 40) { "$where: a caller never finished (dialog up=${host.upGroup}, hold=${host.hold}, bg=${host.background}); log=${log.takeLast(12)}" }
                    if (host.upGroup != null) {
                        host.answer(Act.entries[random.nextInt(Act.entries.size)])
                        sync()
                    } else {
                        host.hold = false
                        yield()
                    }
                }
            }

            suspend fun invariants(where: String, afterExternalChange: Boolean, interruptedStep: Boolean) {
                sync()
                if (afterExternalChange || interruptedStep) listeners.fire()
                // observers deliver on their own coroutines: let them run before comparing
                repeat(4) { yield() }
                testScheduler.runCurrent()
                val launchesBefore: Int = host.launches
                val reads: Map<CommonPermission, PermissionStatus> = perms.associateWith { controller.check(it) }
                assertEquals(launchesBefore, host.launches, "$where: check showed a dialog; log=${log.takeLast(12)}")
                for (p in perms) {
                    val s: String = strings[perms.indexOf(p)]
                    val read: PermissionStatus = reads.getValue(p)
                    assertEquals(s in platform.granted, read == PermissionStatus.Granted, "$where: ${p.id} read $read but granted=${s in platform.granted}; log=${log.takeLast(12)}")
                    if (read is PermissionStatus.Denied && read.explainFirst) {
                        rationaleSeen++
                        if (!host.background) assertTrue(platform.rationale(s), "$where: explain-first without a rationale for ${p.id}; log=${log.takeLast(12)}")
                    }
                    if (read == PermissionStatus.Granted) {
                        granted++
                        sticky -= p
                    }
                    if (read == PermissionStatus.PermanentlyDenied) {
                        permanentlyDenied++
                        sticky += p
                    } else if (p in sticky && read != PermissionStatus.Granted && !host.background) {
                        // a permanent denial decays only through a grant, or while the app cannot ask (background)
                        assertTrue(false, "$where: ${p.id} was permanently denied and now reads $read; log=${log.takeLast(12)}")
                    }
                    // the observer's last emission is what check reads
                    if (!interruptedStep || afterExternalChange) {
                        assertEquals(read, observed.getValue(p).lastOrNull(), "$where: observer of ${p.id} is stale; log=${log.takeLast(12)}")
                    }
                }
                // a controller rebuilt over the same storage reads the same: process death loses nothing
                val twin: AndroidPermissionController = newController()
                for (p in perms) assertEquals(reads.getValue(p), twin.check(p), "$where: a rebuilt controller reads differently for ${p.id}; log=${log.takeLast(12)}")
            }

            repeat(STEPS) { step: Int ->
                executed++
                val where = "$tag step=$step"
                var external = false
                var interruptedStep = false
                val p: CommonPermission = perms[random.nextInt(2)]
                val a: Act = Act.entries[random.nextInt(Act.entries.size)]
                when (random.nextInt(12)) {
                    0, 1, 2 -> {
                        host.hold = false
                        host.act = a
                        log += "request ${p.id} $a"
                        sync()
                        val got: PermissionStatus = controller.request(p)
                        sync()
                        if (!host.background) assertEquals(controller.check(p), got, "$where: request and the next check differ for ${p.id}; log=${log.takeLast(12)}")
                    }
                    3 -> {
                        host.hold = false
                        host.act = a
                        val list: List<CommonPermission> = if (random.nextBoolean()) perms else perms.reversed()
                        log += "batch ${list.map { it.id }} $a"
                        sync()
                        val got: Map<Permission, PermissionStatus> = controller.request(list)
                        sync()
                        assertEquals(list, got.keys.toList(), "$where: batch keys; log=${log.takeLast(12)}")
                        if (!host.background) for (q in list) assertEquals(controller.check(q), got.getValue(q), "$where: batch and the next check differ for ${q.id}; log=${log.takeLast(12)}")
                    }
                    4, 5 -> {
                        val s: String = strings[random.nextInt(2)]
                        external = true
                        if (random.nextBoolean()) {
                            log += "settings grant $s"
                            platform.granted += s
                        } else {
                            log += "settings revoke / one-time grant expires $s"
                            platform.granted -= s
                        }
                    }
                    6 -> {
                        log += "process death"
                        listeners = ResumeListeners()
                        controller = newController()
                        observeAll(controller)
                    }
                    7 -> {
                        host.background = !host.background
                        external = true
                        log += "background=${host.background}"
                    }
                    8, 9 -> {
                        // a dialog that stays up: check meanwhile, then answer, cancel, or die
                        host.hold = true
                        host.act = a
                        val call: Deferred<PermissionStatus> = async { controller.request(p) }
                        if (host.upGroup != null) {
                            interrupted++
                            val upBefore: List<String> = host.upGroup!!
                            log += "dialog up ${p.id}; check meanwhile; then ${a}/${step % 3}"
                            val launches: Int = host.launches
                            assertFalse(controller.check(p) == PermissionStatus.Granted && strings[perms.indexOf(p)] !in platform.granted, "$where: granted while the dialog is up")
                            assertEquals(launches, host.launches, "$where: check launched while a dialog is up")
                            val second: Deferred<PermissionStatus> = async { controller.request(p) }
                            when (step % 3) {
                                0 -> {
                                    host.answer(a)
                                    sync()
                                    settle(where, call, second)
                                }
                                1 -> {
                                    call.cancel()
                                    host.answer(a)
                                    sync()
                                    interruptedStep = true
                                    settle(where, second)
                                }
                                else -> {
                                    // process death with the dialog up: the answer reaches the old controller only
                                    second.cancel()
                                    call.cancel()
                                    listeners = ResumeListeners()
                                    controller = newController()
                                    observeAll(controller)
                                    host.hold = false
                                    host.answer(a)
                                    sync()
                                    interruptedStep = true
                                }
                            }
                            assertTrue(upBefore.isNotEmpty(), "unreachable")
                        } else {
                            settle(where, call)
                        }
                        host.hold = false
                    }
                    else -> {
                        log += "check only"
                    }
                }
                invariants(where, external, interruptedStep)
            }
        }
        return executed
    }

    private companion object {
        const val SEEDS: Int = 6
        const val STEPS: Int = 600
    }
}
