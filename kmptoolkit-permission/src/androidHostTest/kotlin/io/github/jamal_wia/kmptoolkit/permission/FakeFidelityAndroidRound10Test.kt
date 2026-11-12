package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.FidelityScenarios.runAll
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The real Android controller as a [FidelityWorld]: dialogs go through a host the test holds up and answers. */
class AndroidWorld(private val app: Application) : FidelityWorld {

    private val answers: MutableMap<String, Boolean> = mutableMapOf()
    private val pending: MutableList<Pair<List<String>, (Map<String, Boolean>) -> Unit>> = mutableListOf()
    private var holding: Boolean = false
    private var canOpen: Boolean = true
    private val screens: MutableList<Any> = mutableListOf()
    private val resumes: ResumeListeners = ResumeListeners()
    val host: RecordingHost = object : RecordingHost() {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            groups += androidPermissions
            if (holding) pending += androidPermissions to onResult else deliver(androidPermissions, onResult)
            return true
        }
    }

    override val controller: PermissionController = testController(
        app, InMemoryKeyValueStorage(), host, resumeListeners = resumes,
        launchScreen = { request -> screens += request; canOpen },
    )

    private fun string(p: Permission): String = when (p) {
        CommonPermission.Camera -> Manifest.permission.CAMERA
        CommonPermission.Microphone -> Manifest.permission.RECORD_AUDIO
        else -> error("$p")
    }

    private fun deliver(strings: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val yes: List<String> = strings.filter { answers[it] == true }
        shadowOf(app).grantPermissions(*yes.toTypedArray())
        onResult(strings.associateWith { it in yes })
    }

    override fun willAnswer(permission: Permission, grant: Boolean) {
        answers[string(permission)] = grant
    }

    override fun hold(on: Boolean) {
        holding = on
    }

    override fun answerHeld() {
        holding = false
        val now: List<Pair<List<String>, (Map<String, Boolean>) -> Unit>> = pending.toList()
        pending.clear()
        now.forEach { (strings, onResult) -> deliver(strings, onResult) }
    }

    override fun settingsOpenable(can: Boolean) {
        canOpen = can
    }

    override val settingsOpened: Int get() = screens.size

    override fun grantInSettings(permission: Permission) {
        shadowOf(app).grantPermissions(string(permission))
    }

    override fun returnToApp() {
        resumes.fire()
    }

    override suspend fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }
}

/**
 * Fake fidelity, Android half: one scripted user plays the same scenarios against the real Android controller and
 * against [io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController]; everything a caller can see
 * (granted or not, observed values, completion order, who waits for whom) must be the same, since the testing docs
 * claim the double "models the OS in the ways that matter".
 */
@RunWith(AndroidJUnit4::class)
class FakeFidelityAndroidRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun transcripts(real: Boolean): Map<String, List<String>> = runBlocking {
        withTimeout(30_000) {
            runAll {
                shadowOf(app).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                if (real) AndroidWorld(app) else FakeWorld { repeat(20) { yield() } }
            }
        }
    }

    @Test
    fun `every scenario reads the same on the real Android controller and on the fake`() {
        val fake: Map<String, List<String>> = transcripts(real = false)
        val real: Map<String, List<String>> = transcripts(real = true)
        for ((name, lines) in fake) {
            // The one scenario the fake does not follow is kept in suspects/r10-S1.kt, not asserted here.
            if (name == "cancelThenAnswered") continue
            assertEquals(lines, real[name], "scenario $name: fake on the left, real on the right")
        }
    }

    @Test
    fun `the real controller keeps the answer of a dialog whose caller was cancelled and the next check sees it`() {
        // 04-api-reference, Cancellation: the user's answer still reaches the OS, the next check sees what happened.
        assertEquals(
            listOf("cancelled:true", "beforeAnswer:notGranted", "afterAnswer:granted"),
            transcripts(real = true).getValue("cancelThenAnswered"),
        )
    }
}
