package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * A stage a scenario is played on: a real controller over a scripted platform, or [FakePermissionController].
 * The platform is described only through what a user can do (answer a dialog, grant in settings, come back to the
 * app), so one script runs against every implementation of [PermissionController] and the transcripts of what a
 * caller can observe are compared.
 */
interface FidelityWorld {
    val controller: PermissionController

    /** What the user answers to the next dialogs asked for [permission]. */
    fun willAnswer(permission: Permission, grant: Boolean)

    /** While `true`, a dialog stays on screen until [answerHeld]. */
    fun hold(on: Boolean)

    /** The user answers every dialog on screen; dialogs shown from now on are answered at once. */
    fun answerHeld()

    /** Whether the platform can open the settings screen. */
    fun settingsOpenable(can: Boolean)

    /** Settings screens handed to the platform so far. */
    val settingsOpened: Int

    /** The user turns [permission] on in settings. Nothing is observable before [returnToApp]. */
    fun grantInSettings(permission: Permission)

    /** The app is in front again. */
    fun returnToApp()

    /** Lets every launched coroutine run to its next suspension. */
    suspend fun settle()

    /** The states this world can put a permission into. */
    val supported: Set<PreState>

    /** Puts [permission] into [state] the way a user history would. */
    suspend fun setUp(permission: Permission, state: PreState)

    /** System dialogs shown so far, or -1 when the world cannot tell. */
    val dialogsShown: Int
}

/** Where a permission can stand before a request. */
enum class PreState { Granted, PermanentlyDenied, Restricted, Unavailable, DeniedWithRationale }

fun PermissionStatus.tag(): String = if (isGranted) "granted" else "notGranted"

/** The scenarios; each returns the transcript of what the caller observed. */
object FidelityScenarios {

    private val cam: Permission = CommonPermission.Camera
    private val mic: Permission = CommonPermission.Microphone

    suspend fun CoroutineScope.runAll(newWorld: () -> FidelityWorld): Map<String, List<String>> = linkedMapOf(
        "basic" to basic(newWorld(), this),
        "batch" to batch(newWorld(), this),
        "serialized" to serialized(newWorld(), this),
        "cancelThenAnswered" to cancelThenAnswered(newWorld(), this),
        "cancelQueued" to cancelQueued(newWorld(), this),
        "settings" to settings(newWorld(), this),
        "settingsTrip" to settingsTrip(newWorld(), this),
        "refusedThenGrantedLater" to refusedThenGrantedLater(newWorld(), this),
    )

    private fun CoroutineScope.watch(world: FidelityWorld, p: Permission, into: MutableList<String>): Job =
        launch { world.controller.observe(p).collect { into += "observed:${it.tag()}" } }

    suspend fun basic(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        val seen: MutableList<String> = mutableListOf()
        val job: Job = scope.watch(w, cam, seen)
        w.settle()
        t += "fresh:${w.controller.check(cam).tag()}"
        w.willAnswer(cam, true)
        t += "request:${w.controller.request(cam).tag()}"
        t += "check:${w.controller.check(cam).tag()}"
        t += "again:${w.controller.request(cam).tag()}"
        w.settle()
        t += seen
        job.cancel()
        return t
    }

    suspend fun batch(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        w.willAnswer(cam, true)
        w.willAnswer(mic, false)
        val result: Map<Permission, PermissionStatus> = w.controller.request(listOf(cam, mic, cam, mic))
        t += "keys:${result.keys.joinToString { it.id }}"
        t += result.entries.map { "${it.key.id}:${it.value.tag()}" }
        t += "after:${w.controller.check(cam).tag()},${w.controller.check(mic).tag()}"
        t += "empty:${w.controller.request(emptyList()).size}"
        return t
    }

    suspend fun serialized(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        val order: MutableList<String> = mutableListOf()
        w.willAnswer(cam, true)
        w.willAnswer(mic, false)
        w.hold(true)
        val r1: Deferred<PermissionStatus> = scope.async { w.controller.request(cam).also { order += "r1" } }
        w.settle()
        val r2: Deferred<PermissionStatus> = scope.async { w.controller.request(mic).also { order += "r2" } }
        w.settle()
        t += "whileUp:${r1.isCompleted},${r2.isCompleted}"
        w.answerHeld()
        w.settle()
        t += "after:${r1.await().tag()},${r2.await().tag()}"
        t += "order:$order"
        return t
    }

    suspend fun cancelThenAnswered(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        w.willAnswer(cam, true)
        w.hold(true)
        val job: Job = scope.launch { w.controller.request(cam) }
        w.settle()
        job.cancel()
        w.settle()
        t += "cancelled:${job.isCancelled}"
        t += "beforeAnswer:${w.controller.check(cam).tag()}"
        w.answerHeld()
        w.settle()
        t += "afterAnswer:${w.controller.check(cam).tag()}"
        return t
    }

    suspend fun cancelQueued(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        w.willAnswer(cam, true)
        w.willAnswer(mic, true)
        w.hold(true)
        val r1: Deferred<PermissionStatus> = scope.async { w.controller.request(cam) }
        w.settle()
        val r2: Job = scope.launch { w.controller.request(mic) }
        w.settle()
        r2.cancel()
        w.settle()
        w.answerHeld()
        w.settle()
        t += "first:${r1.await().tag()}"
        t += "queuedCancelledNeverAsked:${w.controller.check(mic).tag()}"
        return t
    }

    suspend fun settings(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        t += "open:${w.controller.openSettings(cam)}"
        t += "opened:${w.settingsOpened}"
        w.settingsOpenable(false)
        t += "cannotOpen:${w.controller.openSettings(cam)}"
        return t
    }

    suspend fun settingsTrip(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        val seen: MutableList<String> = mutableListOf()
        val job: Job = scope.watch(w, cam, seen)
        w.settle()
        w.grantInSettings(cam)
        w.settle()
        // Not compared before returnToApp: the fake re-emits at setStatus, which its docs state; a real
        // controller re-reads at the next resume.
        w.returnToApp()
        w.settle()
        t += seen
        t += "check:${w.controller.check(cam).tag()}"
        job.cancel()
        return t
    }

    suspend fun refusedThenGrantedLater(w: FidelityWorld, scope: CoroutineScope): List<String> {
        val t: MutableList<String> = mutableListOf()
        w.willAnswer(cam, false)
        t += "refused:${w.controller.request(cam).tag()}"
        w.willAnswer(cam, true)
        t += "check:${w.controller.check(cam).tag()}"
        w.grantInSettings(cam)
        w.returnToApp()
        t += "afterSettings:${w.controller.check(cam).tag()}"
        return t
    }
}

/** A request from a state that shows no system UI returns that state at once and shows nothing. */
suspend fun noUiRequest(w: FidelityWorld, state: PreState): List<String> {
    w.willAnswer(CommonPermission.Camera, true)
    w.setUp(CommonPermission.Camera, state)
    val shownBefore: Int = w.dialogsShown
    val before: PermissionStatus = w.controller.check(CommonPermission.Camera)
    val got: PermissionStatus = w.controller.request(CommonPermission.Camera)
    val batch: Map<Permission, PermissionStatus> = w.controller.request(listOf(CommonPermission.Camera))
    val t: MutableList<String> = mutableListOf("before:${before::class.simpleName}", "request:${got::class.simpleName}")
    t += "batch:${batch.getValue(CommonPermission.Camera)::class.simpleName}"
    if (state != PreState.DeniedWithRationale) {
        t += "sameAsCheck:${before == got}"
        if (w.dialogsShown >= 0) t += "dialogsShown:${w.dialogsShown - shownBefore}"
    }
    return t
}

fun assertTranscripts(expected: Map<String, List<String>>, actual: Map<String, List<String>>, who: String) {
    assertEquals(expected.keys, actual.keys)
    for ((name, lines) in expected) assertEquals(lines, actual.getValue(name), "$who: scenario $name")
}
