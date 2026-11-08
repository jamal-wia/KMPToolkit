package io.github.jamal_wia.kmptoolkit.audio.player

import android.app.Activity
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** A `MediaPlayer` whose playback parameters exist, as they do on a device (the stock shadow answers `null`). */
@Implements(MediaPlayer::class)
class ParamsShadowMediaPlayer : ShadowMediaPlayer() {

    @Implementation
    fun getPlaybackParams(): PlaybackParams = PlaybackParams()

    @Implementation
    fun setPlaybackParams(params: PlaybackParams) = Unit
}

/** A plain screen that is rotated. */
class PlayerRotationProbeActivity : Activity()

/**
 * An application-scoped player outlives ten recreations of the screen that created it, while every
 * generation of the screen collects its state and position flows (and cancels on destruction). The player
 * keeps playing through all of it, its state is never disturbed by the churn, and no generation of the
 * screen is held by the player, its engine or its flows once destroyed.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ParamsShadowMediaPlayer::class])
class ActivityRotationTest {

    @Test
    fun `a playing player survives ten screen recreations and holds none of the old screens`() {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource("https://example.test/rotation.mp3"),
            ShadowMediaPlayer.MediaInfo(600_000, 0),
        )
        val controller = Robolectric.buildActivity(PlayerRotationProbeActivity::class.java).setup()
        val player: AudioPlayer = createAudioPlayer(controller.get())
        val generations: MutableList<WeakReference<Activity>> = mutableListOf()
        val observed: MutableList<PlayerState> = mutableListOf()
        try {
            runBlocking {
                withTimeout(60_000) {
                    val deadline = TimeSource.Monotonic.markNow() + 20.seconds
                    val loading = launch(Dispatchers.Default) { player.prepare(AudioSource.Remote("https://example.test/rotation.mp3")) }
                    while (player.stateFlow.value !is PlayerState.Ready) {
                        shadowOf(Looper.getMainLooper()).idle()
                        if (deadline.hasPassedNow()) fail("timed out waiting for Ready, state ${player.stateFlow.value}")
                        delay(1L)
                    }
                    loading.join()
                    player.play()

                    repeat(10) { round: Int ->
                        val screen: Activity = controller.get()
                        generations += WeakReference(screen)
                        val collectors = CoroutineScope(Job() + Dispatchers.Default)
                        collectors.launch { player.stateFlow.collect { state: PlayerState -> screen.title = "$state"; observed += state } }
                        collectors.launch { player.playbackPositionFlow.collect { position: Long -> screen.title = "$position" } }
                        delay(25L)
                        assertTrue(player.stateFlow.value is PlayerState.Playing, "round $round: state ${player.stateFlow.value}")
                        collectors.coroutineContext[Job]!!.cancel()
                        collectors.coroutineContext[Job]!!.join()
                        controller.recreate()
                    }
                }
            }
            assertTrue(observed.none { it is PlayerState.Error }, "an error appeared during the rotations: ${observed.filterIsInstance<PlayerState.Error>()}")
        } finally {
            player.release()
        }
        assertEquals(PlayerState.Idle, player.stateFlow.value)
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
