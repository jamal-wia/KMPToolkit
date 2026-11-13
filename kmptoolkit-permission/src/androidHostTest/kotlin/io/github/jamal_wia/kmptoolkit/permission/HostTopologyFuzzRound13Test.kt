package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** A component activity of one of two classes whose task id is an intent extra, so a recreated instance keeps it. */
class Round13TopoActivity : ComponentActivity() {
    override fun getTaskId(): Int = intent?.getIntExtra("task", 1) ?: 1
}

/** The same, a second class. */
class Round13OtherTopoActivity : ComponentActivity() {
    override fun getTaskId(): Int = intent?.getIntExtra("task", 1) ?: 1
}

/** An activity that is not a `ComponentActivity`: a plain framework activity of some SDK. */
class Round13PlainActivity : Activity()

/**
 * Round 13 (E1, topology): the real host with the dialog up and a seeded random topology around the activity
 * the dialog came from: several windows resumed at once (split screen), activities of two classes in several
 * tasks (including the origin's own class in other tasks), plain `Activity` windows, rotation, pause and resume of
 * any of them, `finish()` called some steps before the destroy, other activities reclaimed by the system.
 *
 * Contract used (the host's KDoc and 05-platform-notes), nothing read off the implementation:
 * - the request ends, once and empty, when and only when the activity the dialog came from is finished: it is
 *   destroyed while finishing, or another `ComponentActivity` resumes while it is finishing;
 * - otherwise it stays pending and the user's answer resolves it once with that answer;
 * - a launch is refused when the activity the access reports (the one resumed most recently, alive) is not a
 *   `ComponentActivity` or there is none, and accepted otherwise;
 * - after the request ended the host takes the next one.
 */
@RunWith(AndroidJUnit4::class)
class HostTopologyFuzzRound13Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val live: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val seed: Long = Integer.getInteger("kmptoolkit.test.seed", 13013).toLong()

    @AfterTest
    fun tearDown() {
        access.release()
        live.forEach { it.close() }
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private enum class Kind { Topo, OtherTopo, Plain }

    private inner class Window(val kind: Kind, val task: Int) {
        val controller: ActivityController<out Activity> = build()
        var resumed: Boolean = false
        var finishing: Boolean = false
        var destroyed: Boolean = false

        /** Whether this instance had a resume event while the request was pending: the host registered with it. */
        var attached: Boolean = false
        val isComponent: Boolean get() = kind != Kind.Plain
        val activity: Activity get() = controller.get()

        private fun build(): ActivityController<out Activity> {
            val intent: Intent = Intent().putExtra("task", task)
            val made: ActivityController<out Activity> = when (kind) {
                Kind.Topo -> Robolectric.buildActivity(Round13TopoActivity::class.java, intent)
                Kind.OtherTopo -> Robolectric.buildActivity(Round13OtherTopoActivity::class.java, intent)
                Kind.Plain -> Robolectric.buildActivity(Round13PlainActivity::class.java, intent)
            }
            live += made
            return made
        }
    }

    @Test
    fun `a random topology of windows and tasks around the permission dialog's activity ends the request only when its own activity is finished`() = walks(forResult = false)

    @Test
    fun `the same topology around a screen started for a result ends the request only when its own activity is finished`() = walks(forResult = true)

    private fun walks(forResult: Boolean) {
        shadowOf(application).denyPermissions(camera)
        // What the caller is told when the request ends with its activity (empty answer, RESULT_CANCELED) and when the user answers.
        val endedValue: Any = if (forResult) Activity.RESULT_CANCELED else emptyMap<String, Boolean>()
        val answeredValue: Any = if (forResult) Activity.RESULT_OK else mapOf(camera to false)
        val random = Random(seed)
        val stats: MutableMap<String, Int> = mutableMapOf()
        fun count(name: String) {
            stats[name] = (stats[name] ?: 0) + 1
        }
        repeat(150) { walk ->
            val windows: MutableList<Window> = mutableListOf()
            // Resume order, oldest first: the order the tracker is told about.
            val order: MutableList<Window> = mutableListOf()
            val trail: MutableList<String> = mutableListOf("seed=$seed walk=$walk")

            fun start(window: Window) {
                windows += window
                window.controller.setup()
                window.resumed = true
                order.remove(window)
                order += window
            }

            var origin = Window(Kind.Topo, task = 1)
            start(origin)
            // Set when the system reclaims the origin (destroyed, not finishing): the class and task the host waits for.
            var lost: Pair<Kind, Int>? = null
            // False once the origin was reclaimed: Robolectric's new instance has no saved registry to deliver the answer.
            var canAnswer = true
            val host = ActivityResultPermissionRequestHost(access)
            val answers: MutableList<Any> = mutableListOf()

            fun launchInto(sink: MutableList<Any>): Boolean =
                if (forResult) host.launchForResult(Intent("com.example.ROLE")) { sink += it } else host.launch(listOf(camera)) { sink += it }

            assertTrue(launchInto(answers), "$trail: the request was not launched")
            val requestCode: Int =
                if (forResult) assertNotNull(shadowOf(origin.activity).nextStartedActivityForResult).requestCode
                else assertNotNull(shadowOf(origin.activity).lastRequestedPermission).requestCode
            val requestedPermissions: Array<String> =
                if (forResult) emptyArray() else assertNotNull(shadowOf(origin.activity).lastRequestedPermission).requestedPermissions

            /** The user answers: delivered through the registry of the origin's current instance. */
            fun deliverAnswer() {
                if (forResult) {
                    (origin.activity as ComponentActivity).activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, Intent())
                    return
                }
                endPlatformRequest(origin.activity)
                @Suppress("DEPRECATION")
                origin.activity.onRequestPermissionsResult(requestCode, requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
                endPlatformRequest(origin.activity)
            }
            // 0 pending, 1 ended empty (origin finished), 2 answered by the user.
            var outcome = 0
            origin.attached = true

            fun pendingNow(): Boolean = outcome == 0

            /**
             * A resume event of [window]: while the origin is lost, the first ComponentActivity of its class and task
             * is its recreated instance; while the origin is finishing and alive, any other ComponentActivity ends the request.
             */
            fun resumeEvent(window: Window, fresh: Boolean = !window.attached) {
                val gone: Pair<Kind, Int>? = lost
                if (pendingNow() && window.isComponent) window.attached = true
                // Only a NEW registration can be the recreated origin: an older sibling that resumes again is not.
                if (pendingNow() && fresh && gone != null && window.isComponent && window.kind == gone.first && window.task == gone.second) {
                    origin = window
                    lost = null
                    return
                }
                if (pendingNow() && window !== origin && window.isComponent && origin.finishing && !origin.destroyed) outcome = 1
            }

            fun expectState(after: String) {
                trail += after
                when (outcome) {
                    1 -> assertEquals(listOf(endedValue), answers, "$trail: the request must have ended once, empty")
                    2 -> assertEquals(listOf(answeredValue), answers, "$trail: the answered request must stay answered once")
                    else -> assertEquals(emptyList<Any>(), answers, "$trail: the request ended without its activity being finished")
                }
            }

            repeat(random.nextInt(2, 14)) {
                val others: List<Window> = windows.filter { !it.destroyed }
                when (random.nextInt(12)) {
                    0, 9, 10 -> {
                        val kind: Kind = if (random.nextInt(3) == 0) Kind.Topo else Kind.entries[random.nextInt(Kind.entries.size)]
                        val task: Int = random.nextInt(1, 4)
                        val window = Window(kind, task)
                        start(window)
                        resumeEvent(window)
                        expectState("spawn $kind task $task")
                    }

                    1 -> others.filter { it.resumed }.randomOrNull(random)?.let { window ->
                        window.controller.pause()
                        window.resumed = false
                        order.remove(window)
                        expectState("pause ${window.kind}/${window.task}${if (window === origin) " (origin)" else ""}")
                    }

                    2 -> others.filter { !it.resumed }.randomOrNull(random)?.let { window ->
                        window.controller.resume()
                        window.resumed = true
                        order.remove(window)
                        order += window
                        resumeEvent(window)
                        expectState("resume ${window.kind}/${window.task}${if (window === origin) " (origin)" else ""}")
                    }

                    3 -> others.filter { it.resumed && it.isComponent && !it.finishing }.randomOrNull(random)?.let { window ->
                        window.controller.recreate()
                        assertEquals(window.task, window.activity.taskId, "$trail: the recreated instance lost its task")
                        order.remove(window)
                        order += window
                        resumeEvent(window, fresh = true)
                        expectState("rotate ${window.kind}/${window.task}${if (window === origin) " (origin)" else ""}")
                    }

                    4 -> others.filter { !it.finishing }.randomOrNull(random)?.let { window ->
                        window.activity.finish()
                        window.finishing = true
                        expectState("finish() ${window.kind}/${window.task}${if (window === origin) " (origin)" else ""}")
                    }

                    5 -> others.filter { it.finishing }.randomOrNull(random)?.let { window ->
                        if (window.resumed) {
                            window.controller.pause()
                            window.resumed = false
                            order.remove(window)
                        }
                        window.controller.stop().destroy()
                        window.destroyed = true
                        if (window === origin && pendingNow()) outcome = 1
                        expectState("destroy finishing ${window.kind}/${window.task}${if (window === origin) " (origin)" else ""}")
                    }

                    6 -> others.filter { it !== origin && !it.finishing }.randomOrNull(random)?.let { window ->
                        // The system reclaims a background activity: destroyed, not finishing.
                        if (window.resumed) {
                            window.controller.pause()
                            order.remove(window)
                        }
                        window.controller.stop().destroy()
                        window.resumed = false
                        window.destroyed = true
                        expectState("reclaim ${window.kind}/${window.task}")
                    }

                    11 -> if (pendingNow() && canAnswer && lost == null && !origin.finishing && !origin.destroyed) {
                        // "Don't keep activities", or a reclaim: the origin is destroyed with the dialog up, not finishing.
                        if (origin.resumed) {
                            origin.controller.pause()
                            order.remove(origin)
                        }
                        origin.controller.stop().destroy()
                        origin.resumed = false
                        origin.destroyed = true
                        lost = origin.kind to origin.task
                        canAnswer = false
                        expectState("reclaim origin ${origin.kind}/${origin.task}")
                    }

                    7 -> {
                        // A launch while the request is pending is refused, and shows nothing.
                        if (pendingNow()) {
                            assertTrue(!launchInto(mutableListOf()), "$trail: a second launch while pending was accepted")
                            expectState("launch refused while pending")
                        }
                    }

                    else -> if (pendingNow() && canAnswer && !origin.destroyed && random.nextInt(3) == 0) {
                        // The user answers; the registry of the origin's current instance delivers it.
                        deliverAnswer()
                        trail += "answer"
                        assertEquals(listOf(answeredValue), answers, "$trail: the answer did not resolve the request once")
                        outcome = 2
                    }
                }
            }

            // Settle: either the user answers now, or the request already ended.
            var pendingForever = false
            if (pendingNow() && !canAnswer) {
                if (lost != null) {
                    pendingForever = true
                } else {
                    // The recreated origin finishes: that ends the request, once, empty.
                    trail += "finish the recreated origin"
                    origin.activity.finish()
                    origin.finishing = true
                    if (origin.resumed) {
                        origin.controller.pause()
                        origin.resumed = false
                        order.remove(origin)
                    }
                    origin.controller.stop().destroy()
                    origin.destroyed = true
                    outcome = 1
                }
            }
            if (pendingNow() && !pendingForever) {
                assertTrue(!origin.destroyed, "$trail: model error, origin gone with the request pending")
                deliverAnswer()
                trail += "final answer"
                assertEquals(listOf(answeredValue), answers, "$trail: the final answer did not resolve the request once")
                outcome = 2
            }
            expectState("settled")
            count(if (pendingForever) "lost-forever" else if (outcome == 1) "ended-by-finish" else "answered")
            if (canAnswer.not()) count("origin-reclaimed")
            if (canAnswer.not() && lost == null) count("origin-recreated-after-reclaim")
            if (windows.count { it.resumed && !it.destroyed } >= 2) count("several-windows-resumed")
            if (windows.any { it !== origin && it.kind == Kind.Topo && !it.destroyed }) count("same-class-stranger-alive")
            if (windows.any { it !== origin && it.kind == Kind.Topo && it.task != 1 && !it.destroyed }) count("same-class-other-task-alive")

            // The host is free: a launch is accepted exactly when the newest live resumed window is a ComponentActivity.
            val newest: Window? = order.lastOrNull { !it.destroyed && !it.finishing }
            // A request still pending (its origin lost and never recreated) keeps the host busy.
            val expectAccepted: Boolean = !pendingForever && newest?.isComponent == true
            count(if (expectAccepted) "next-launch-accepted" else "next-launch-refused")
            val accepted: Boolean = launchInto(mutableListOf())
            assertEquals(
                expectAccepted,
                accepted,
                "$trail: next launch accepted=$accepted, newest=${newest?.kind}/${newest?.task}",
            )
            windows.filter { !it.destroyed }.forEach { window ->
                if (!window.finishing) window.activity.finish()
                if (window.resumed) window.controller.pause()
                window.controller.stop().destroy()
                window.destroyed = true
            }
            windows.forEach { it.controller.close(); live.remove(it.controller) }
        }
        // Non-vacuity: every kind of situation the test claims to cover really happened.
        listOf(
            "ended-by-finish", "answered", "several-windows-resumed", "same-class-stranger-alive",
            "same-class-other-task-alive", "next-launch-accepted", "next-launch-refused",
            "origin-reclaimed", "origin-recreated-after-reclaim", "lost-forever",
        ).forEach { name -> assertTrue((stats[name] ?: 0) >= 4, "seed=$seed: situation '$name' happened ${stats[name]} times: $stats") }
    }
}
