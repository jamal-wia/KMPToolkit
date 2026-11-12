package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * High contention on the Android controller from many coroutines on a multi-threaded dispatcher while the test thread plays the
 * main thread and the user: it pumps the main looper (the controller posts its dialog launches there) and answers the dialogs
 * the host shows. Callers request the same and different permissions, batches, checks and observers, and other coroutines cancel
 * them at random moments, including while a dialog is up and while its answer arrives. Invariants once the storm has ended: no
 * job is stuck (hard real-time bounds), only cancellation escapes a call, the host never saw two dialogs at once from one
 * controller, every status a request returned is one the platform state allows, no resume listener is left registered, the
 * controller reads the platform state and a fresh request on every permission still completes.
 */
@RunWith(AndroidJUnit4::class)
class PermissionContentionRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val strings: List<String> = listOf(camera, microphone)
    private val completed: AtomicInteger = AtomicInteger()
    private val answeredTotal: AtomicInteger = AtomicInteger()

    /** Resume listeners that tolerate callers on several threads, as the real tracker does (the shared fixture does not). */
    private class SafeListeners : ResumeListeners() {
        private val registered: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()
        val live: Int get() = registered.size

        override fun add(listener: () -> Unit): ActivitySubscription {
            registered += listener
            return object : ActivitySubscription {
                override fun cancel() {
                    registered -= listener
                }
            }
        }
    }

    /** A host whose dialogs the test thread answers; it records overlapping launches and what it was asked. */
    private class PumpHost(private val application: Application, private val refuseOverlap: Boolean) : PermissionRequestHost {
        private class Dialog(val group: List<String>, val onResult: (Map<String, Boolean>) -> Unit)

        private val queue: ConcurrentLinkedQueue<Dialog> = ConcurrentLinkedQueue()
        val launches: AtomicInteger = AtomicInteger()
        val overlaps: AtomicInteger = AtomicInteger()
        val answered: AtomicInteger = AtomicInteger()
        val up: Int get() = queue.size

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launches.incrementAndGet()
            if (queue.isNotEmpty()) {
                overlaps.incrementAndGet()
                if (refuseOverlap) return false
            }
            queue += Dialog(androidPermissions, onResult)
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false

        /** The user answers the oldest dialog: [allow] grants all of it, otherwise nothing is granted. */
        fun answerOne(allow: Boolean): Boolean {
            val dialog: Dialog = queue.poll() ?: return false
            if (allow) shadowOf(application).grantPermissions(*dialog.group.toTypedArray())
            answered.incrementAndGet()
            dialog.onResult(dialog.group.associateWith { s: String -> application.checkSelfPermission(s) == 0 })
            return true
        }
    }

    private fun pump(host: List<PumpHost>, random: Random, jobs: List<Job>, what: String) {
        val deadline: Long = System.nanoTime() + 90_000_000_000L
        while (jobs.any { it.isActive }) {
            looper.idle()
            for (h: PumpHost in host) if (random.nextInt(3) == 0) h.answerOne(allow = random.nextInt(4) == 0)
            if (random.nextInt(6) == 0) Thread.sleep(1)
            assertTrue(System.nanoTime() < deadline, "$what: callers are stuck; dialogs up=${host.map { it.up }}")
        }
        looper.idle()
    }

    private fun allowed(status: PermissionStatus): Boolean =
        status == PermissionStatus.NotDetermined || status is PermissionStatus.Denied || status == PermissionStatus.PermanentlyDenied

    private fun storm(controllers: List<AndroidPermissionController>, hosts: List<PumpHost>, round: Int, escaped: ConcurrentLinkedQueue<String>) {
        val perms: List<CommonPermission> = listOf(CommonPermission.Camera, CommonPermission.Microphone)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bad: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val jobs: List<Job> = List(COROUTINES) { index: Int ->
            scope.launch {
                val random = Random(round * 7919L + index)
                val controller: AndroidPermissionController = controllers[random.nextInt(controllers.size)]
                val children: MutableList<Job> = mutableListOf()
                repeat(OPS) {
                    val p: CommonPermission = perms[random.nextInt(2)]
                    fun guarded(block: suspend () -> Unit): Job = launch {
                        try {
                            block()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                    when (random.nextInt(100)) {
                        in 0..24 -> children += guarded {
                            val string: String = strings[perms.indexOf(p)]
                            val before: Boolean = application.checkSelfPermission(string) == 0
                            val status: PermissionStatus = controller.request(p)
                            completed.incrementAndGet()
                            val after: Boolean = application.checkSelfPermission(string) == 0
                            // Grants only ever arrive in this storm: granted before the call means Granted, Granted means granted after.
                            if (before && status != PermissionStatus.Granted) bad += "request ${p.id} -> $status though it was granted before"
                            if (status == PermissionStatus.Granted && !after) bad += "request ${p.id} -> Granted though the platform denies it"
                            if (status !is PermissionStatus.Granted && !allowed(status)) bad += "request ${p.id} -> $status"
                        }
                        in 25..34 -> children += guarded {
                            val got: Map<Permission, PermissionStatus> = controller.request(perms.shuffled(random))
                            if (got.keys != perms.toSet()) bad += "batch keys ${got.keys}"
                        }
                        in 35..49 -> children += guarded { controller.check(p) }
                        in 50..54 -> children += guarded { controller.observe(p).first() }
                        in 55..84 -> if (children.isNotEmpty()) children[random.nextInt(children.size)].cancel()
                        else -> delay(random.nextLong(0, 2))
                    }
                    if (random.nextInt(5) == 0) delay(1)
                }
                children.forEach { it.join() }
            }
        }
        try {
            pump(hosts, Random(round.toLong()), jobs, "round $round")
        } finally {
            scope.cancel()
        }
        assertEquals(emptyList(), bad.toList(), "round $round: a call returned a status the platform state forbids")
        answeredTotal.addAndGet(hosts.sumOf { it.answered.get() })
    }

    private fun newController(host: PumpHost, storage: KeyValueStorage, listeners: SafeListeners, prefix: String): AndroidPermissionController =
        testController(
            context = application,
            storage = storage,
            host = host,
            keyPrefix = prefix,
            sdkInt = 34,
            shouldShowRationale = { false },
            resumeListeners = listeners,
        )

    private fun endState(controllers: List<AndroidPermissionController>, hosts: List<PumpHost>, listeners: List<SafeListeners>, round: Int) {
        for (host: PumpHost in hosts) assertEquals(0, host.up, "round $round: a dialog is left up with no caller")
        for (l: SafeListeners in listeners) assertEquals(0, l.live, "round $round: a resume listener is left registered")
        val perms: List<CommonPermission> = listOf(CommonPermission.Camera, CommonPermission.Microphone)
        for (c: AndroidPermissionController in controllers) {
            for (p: CommonPermission in perms) {
                val granted: Boolean = application.checkSelfPermission(strings[perms.indexOf(p)]) == 0
                val status: PermissionStatus = runBlocking { withTimeout(5_000) { c.check(p) } }
                assertEquals(granted, status == PermissionStatus.Granted, "round $round: check(${p.id}) = $status but the platform says granted=$granted")
            }
        }
        // Every permission still accepts a fresh request, and a granted one answers without a dialog.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (c: AndroidPermissionController in controllers) {
                val fresh: List<Job> = perms.map { p: CommonPermission -> scope.launch { c.request(p) } }
                pump(hosts, Random(99L), fresh, "round $round fresh requests")
            }
        } finally {
            scope.cancel()
        }
    }

    private fun reset() {
        shadowOf(application).denyPermissions(camera, microphone)
        application.declareInManifest(camera, microphone)
    }

    @Test
    fun `one controller under a storm of same and different permissions with cancels at random moments keeps its invariants`() {
        repeat(ROUNDS) { round: Int ->
            reset()
            val host = PumpHost(application, refuseOverlap = false)
            val listeners = SafeListeners()
            val storage: KeyValueStorage = InMemoryKeyValueStorage()
            val controller: AndroidPermissionController = newController(host, storage, listeners, "one")
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            storm(listOf(controller), listOf(host), round, escaped)
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped a call")
            assertEquals(0, host.overlaps.get(), "round $round: one controller showed two dialogs at once")
            endState(listOf(controller), listOf(host), listOf(listeners), round)
        }
        assertWorked("one controller")
    }

    @Test
    fun `two controllers in one process over one storage and one host never hang and agree with the platform afterwards`() {
        repeat(ROUNDS) { round: Int ->
            reset()
            val host = PumpHost(application, refuseOverlap = true)
            val listenersA = SafeListeners()
            val listenersB = SafeListeners()
            val storage: KeyValueStorage = InMemoryKeyValueStorage()
            val a: AndroidPermissionController = newController(host, storage, listenersA, "shared")
            val b: AndroidPermissionController = newController(host, storage, listenersB, "shared")
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            storm(listOf(a, b), listOf(host), round + 100, escaped)
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped a call")
            endState(listOf(a, b), listOf(host), listOf(listenersA, listenersB), round)
        }
        assertWorked("two controllers one host")
    }

    @Test
    fun `two controllers with their own hosts and storage run side by side without crosstalk`() {
        repeat(ROUNDS) { round: Int ->
            reset()
            val hostA = PumpHost(application, refuseOverlap = false)
            val hostB = PumpHost(application, refuseOverlap = false)
            val listenersA = SafeListeners()
            val listenersB = SafeListeners()
            val a: AndroidPermissionController = newController(hostA, InMemoryKeyValueStorage(), listenersA, "a")
            val b: AndroidPermissionController = newController(hostB, InMemoryKeyValueStorage(), listenersB, "b")
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            storm(listOf(a, b), listOf(hostA, hostB), round + 200, escaped)
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped a call")
            assertEquals(0, hostA.overlaps.get(), "round $round: controller A showed two dialogs at once")
            assertEquals(0, hostB.overlaps.get(), "round $round: controller B showed two dialogs at once")
            endState(listOf(a, b), listOf(hostA, hostB), listOf(listenersA, listenersB), round)
        }
        assertWorked("two controllers two hosts")
    }

    private fun assertWorked(what: String) {
        // Non-vacuity: the storms really completed requests and really showed and answered dialogs.
        assertTrue(completed.get() > ROUNDS * 20, "$what: only ${completed.get()} requests completed")
        assertTrue(answeredTotal.get() > ROUNDS * 2, "$what: only ${answeredTotal.get()} dialogs were answered")
    }

    private companion object {
        const val ROUNDS: Int = 5
        const val COROUTINES: Int = 20
        const val OPS: Int = 80
    }
}
