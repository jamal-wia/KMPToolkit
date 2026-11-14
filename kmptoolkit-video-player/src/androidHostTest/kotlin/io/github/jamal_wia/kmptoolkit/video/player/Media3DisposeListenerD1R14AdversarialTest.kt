package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * D1 round 14, question 1 (listener registrations balanced): the engine registers its `Player.Listener` on the ExoPlayer it
 * builds and takes it off in `dispose()`. With the ExoPlayer itself still referenced (a consumer's surface, a leaked
 * reference), a disposed engine must not stay reachable through it: the registration is balanced by the removal.
 * Probe for the open mutant M26 of round 6 (dispose without `removeListener`).
 */
@RunWith(AndroidJUnit4::class)
class Media3DisposeListenerD1R14AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")
    private val retained: MutableList<ExoPlayer> = mutableListOf()

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    private fun disposedEngineRef(): WeakReference<Any> {
        val engine: Media3VideoEngine = newEngine(context, created = { retained += it })
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined)
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        pump(2)
        player.release()
        pump(3)
        return WeakReference(engine)
    }

    @Test
    fun `a disposed engine is not reachable through the ExoPlayer that is still referenced`() {
        val ref: WeakReference<Any> = disposedEngineRef()
        assertNotNull(retained.singleOrNull(), "the engine builds exactly one ExoPlayer")
        var rounds: Int = 0
        while (ref.get() != null && rounds < 60) {
            System.gc()
            pump(1)
            rounds++
        }
        assertEquals(null, ref.get(), "the disposed engine is still reachable through the retained ExoPlayer after $rounds collections")
    }
}
