package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.Shadows

/**
 * A plain screen that is rotated. [lastSeen] is what the collectors write instead of touching the
 * activity from a worker thread (`setTitle` throws `CalledFromWrongThreadException` on action-bar themes).
 */
class RecorderRotationProbeActivity : Activity() {
    @Volatile
    var lastSeen: String? = null
}

/**
 * A recorder owned by something that outlives the screen records through ten recreations of that screen,
 * each generation collecting the state, the elapsed time and both level flows and cancelling on
 * destruction. The recording must be unaffected (still recording, elapsed only growing, no error), the
 * stop must deliver the file, and no generation of the screen may stay reachable.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [StrictShadowMediaRecorder::class])
class ActivityRotationTest {

    @Test
    fun `a recording survives ten screen recreations and holds none of the old screens`() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(RecorderRotationProbeActivity::class.java).setup()
        val recorder: AudioRecorder = createAudioRecorder(controller.get())
        val generations: MutableList<WeakReference<Activity>> = mutableListOf()
        val failures: CopyOnWriteArrayList<Throwable> = CopyOnWriteArrayList()
        try {
            runBlocking {
                withTimeout(60_000) {
                    check(recorder.prepare() is RecorderResult.Success)
                    check(recorder.start().isSuccess)
                    var lastElapsed = recorder.elapsed.value
                    repeat(10) { round: Int ->
                        val screen: RecorderRotationProbeActivity = controller.get()
                        generations += WeakReference(screen)
                        val collectors = CoroutineScope(
                            Job() + Dispatchers.Default +
                                CoroutineExceptionHandler { _, failure: Throwable -> failures += failure },
                        )
                        collectors.launch { recorder.state.collect { screen.lastSeen = "$it" } }
                        collectors.launch { recorder.elapsed.collect { screen.lastSeen = "$it" } }
                        collectors.launch { recorder.level.collect { screen.lastSeen = "$it" } }
                        collectors.launch { recorder.levelSamples.collect { screen.lastSeen = "$it" } }
                        delay(130L)
                        val seenDeadline = TimeSource.Monotonic.markNow() + 5.seconds
                        while (screen.lastSeen == null && !seenDeadline.hasPassedNow()) delay(10L)
                        assertTrue(screen.lastSeen != null, "round $round: the collectors never ran")
                        val state: RecorderState = recorder.state.value
                        assertTrue(state is RecorderState.Recording, "round $round: state $state")
                        val elapsed = recorder.elapsed.value
                        assertTrue(elapsed >= lastElapsed, "round $round: elapsed went from $lastElapsed to $elapsed")
                        lastElapsed = elapsed
                        collectors.coroutineContext[Job]!!.cancel()
                        collectors.coroutineContext[Job]!!.join()
                        controller.recreate()
                    }
                    assertTrue(lastElapsed >= 600.milliseconds, "elapsed only reached $lastElapsed in about 1.3 s of recording")
                    val stopped = recorder.stop()
                    assertTrue(stopped.isSuccess, "stop gave $stopped")
                    assertTrue(recorder.state.value is RecorderState.Completed)
                }
            }
        } finally {
            recorder.release()
        }
        assertEquals(RecorderState.Released, recorder.state.value)
        assertTrue(failures.isEmpty(), "a collector crashed: $failures")
        controller.pause().stop().destroy()
        var alive = generations.size
        repeat(80) {
            if (alive > 0) {
                System.gc()
                System.runFinalization()
                alive = generations.count { it.get() != null }
                if (alive > 0) Thread.sleep(50)
            }
        }
        assertEquals(0, alive, "$alive of ${generations.size} destroyed screens are still reachable")
    }
}
