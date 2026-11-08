package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.SOURCE_DURATION_MS
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * seekTo is documented to keep the state: "Playing stays Playing, Paused stays Paused, Ready stays
 * Ready, Completed becomes Paused". A seek onto the very end is the edge: ExoPlayer reaches its ended
 * state from it.
 */
@RunWith(AndroidJUnit4::class)
class Media3SeekToEndAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")
    private val player: VideoPlayer = createVideoPlayer(
        engine = newEngine(context),
        coroutineContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() {
        player.release()
        ShadowLooper.idleMainLooper()
    }

    private fun settle() = repeat(10) { ShadowLooper.idleMainLooper() }

    @Test
    fun `a paused player seeked onto the end stays paused`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        settle()
        player.pause()
        player.seekTo(Long.MAX_VALUE)
        settle()
        assertEquals(VideoPlayerState.Paused(SOURCE_DURATION_MS, SOURCE_DURATION_MS), player.stateFlow.value)
    }

    @Test
    fun `a ready player seeked onto the end stays ready`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.seekTo(Long.MAX_VALUE)
        settle()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
    }

    @Test
    fun `playing from the end of a paused seek plays the source from the start`() {
        runSuspending { player.prepare(source) }.getOrThrow()
        player.play()
        settle()
        player.pause()
        player.seekTo(Long.MAX_VALUE)
        settle()
        player.play()
        settle()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
    }
}
