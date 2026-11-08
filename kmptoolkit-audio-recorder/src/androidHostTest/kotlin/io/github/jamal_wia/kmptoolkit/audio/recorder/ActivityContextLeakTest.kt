package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows

/** A plain screen the leak probe creates and destroys. */
class RecorderLeakProbeActivity : Activity()

/**
 * A recorder created from a screen and kept across its destruction (a view model surviving a
 * configuration change) must not hold the screen, whatever state it was left in.
 */
@RunWith(AndroidJUnit4::class)
class ActivityContextLeakTest {

    private fun createRecordUseAndDestroy(holder: Array<AudioRecorder?>): WeakReference<Activity> {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(RecorderLeakProbeActivity::class.java).setup()
        val activity: Activity = controller.get()
        val recorder: AudioRecorder = createAudioRecorder(activity)
        holder[0] = recorder
        runBlocking {
            withTimeout(10_000) {
                check(recorder.prepare() is RecorderResult.Success)
                check(recorder.start().isSuccess)
                check(recorder.pause().isSuccess)
            }
        }
        val reference = WeakReference(activity)
        controller.pause().stop().destroy()
        return reference
    }

    @Test
    fun `a destroyed screen is not kept alive by a recorder that is still paused`() {
        val holder = arrayOfNulls<AudioRecorder>(1)
        val reference: WeakReference<Activity> = createRecordUseAndDestroy(holder)

        var collected = false
        repeat(60) {
            if (!collected) {
                System.gc()
                System.runFinalization()
                collected = reference.get() == null
                if (!collected) Thread.sleep(50)
            }
        }
        try {
            assertTrue(collected, "the destroyed activity is still reachable through the recorder or its engine")
        } finally {
            runBlocking { withTimeout(10_000) { holder[0]!!.cancel() } }
            holder[0]!!.release()
        }
    }
}
