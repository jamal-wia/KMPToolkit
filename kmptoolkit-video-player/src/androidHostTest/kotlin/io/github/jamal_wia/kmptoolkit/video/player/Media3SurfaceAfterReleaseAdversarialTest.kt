package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.launchOnMain
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Media3 accessors and the engine hand to a surface or to ExoPlayer around release and with
 * hostile rates. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3SurfaceAfterReleaseAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    @Test
    fun `a player released in the middle of its first load hands out no ExoPlayer afterwards`() {
        val created: MutableList<ExoPlayer> = mutableListOf()
        val player: VideoPlayer = createVideoPlayer(
            engine = newEngine(context, created = { created += it }),
            coroutineContext = Dispatchers.Unconfined,
        )
        val call: Job = launchOnMain { player.prepare(source) }
        // The prepare is parked on the looper: nothing ran yet.
        player.release()

        assertNull(player.media3PlayerOrNull(), "a released player must not hand out (or create) an ExoPlayer")
        runMainLooperUntil { call.isCompleted }
        pump(3)
        assertNull(player.media3PlayerOrNull())
        assertTrue(created.all { it.isReleased }, "every ExoPlayer the engine built must be released: ${created.map { it.isReleased }}")
    }

    @Test
    fun `a rate that is not a finite positive number never reaches ExoPlayer`() {
        val created: MutableList<ExoPlayer> = mutableListOf()
        val engine: Media3VideoEngine = newEngine(context, created = { created += it })
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
        try {
            runSuspending { player.prepare(source) }.getOrThrow()
            player.play()
            pump(3)
            for (rate in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 0f, -1f)) {
                engine.setSpeed(rate)
                // Bounded: a rate that spun the player's work loop would never come back from here.
                pump(3)
                assertEquals(1f, created.single().playbackParameters.speed, "ExoPlayer received the rate $rate")
            }
            engine.setSpeed(2f)
            pump(3)
            assertEquals(2f, created.single().playbackParameters.speed)
        } finally {
            player.release()
            pump(2)
        }
    }
}
