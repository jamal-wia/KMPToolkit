package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random lifecycle sequences over the real Media3 engine (a Media3 test ExoPlayer, a fake source):
 * prepare, cancel, unload, release, transport and settings calls on the main thread, with the looper
 * run in between and the position poll ticking on another thread. Whatever the order, once things go
 * quiet nothing may be left half-loaded in the ExoPlayer, no call may be stuck, and the final
 * release must release the ExoPlayer.
 */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class Media3PlayerLifecycleFuzzAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Asset("clips/intro.mp4")

    @Test
    fun `random lifecycle sequences over the Media3 engine leave nothing half-loaded and release the ExoPlayer`() {
        repeat(SEEDS) { seed: Int -> runSeed(seed) }
    }

    private fun runSeed(seed: Int) {
        val random = Random(seed)
        var exo: ExoPlayer? = null
        val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { exo = it })
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = VideoPlayerConfig(positionUpdateIntervalMs = 5L),
            coroutineContext = Dispatchers.Unconfined,
        )
        val calls: MutableList<Job> = mutableListOf()
        val log = StringBuilder()
        var released = false

        repeat(OPS) {
            val op: Int = random.nextInt(100)
            val name: String = when {
                op < 25 -> {
                    calls += GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { player.prepare(source) }
                    "prepare"
                }
                op < 35 -> {
                    if (calls.isNotEmpty()) calls.random(random).cancel()
                    "cancel"
                }
                op < 45 -> player.unload().let { "unload" }
                op < 47 -> {
                    player.release()
                    released = true
                    "release"
                }
                op < 57 -> player.play().let { "play" }
                op < 62 -> player.pause().let { "pause" }
                op < 70 -> player.seekTo(random.nextLong(0L, 6_000L)).let { "seek" }
                op < 74 -> (if (random.nextBoolean()) player.replay() else player.stop()).let { "replay/stop" }
                op < 78 -> {
                    player.setVolume(random.nextFloat())
                    player.setMuted(random.nextBoolean())
                    player.setRepeatMode(RepeatMode.entries.random(random))
                    "settings"
                }
                op < 90 -> ShadowLooper.idleMainLooper().let { "idle" }
                else -> shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(random.nextLong(1L, 400L))).let { "time" }
            }
            log.append(name).append(';')
        }

        // Quiesce.
        Media3TestSupport.runMainLooperUntil { calls.all { it.isCompleted } }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100L)) }
        val stuck: Int = calls.count { it.isActive }
        if (stuck > 0) fail("seed $seed: $stuck prepare call(s) never returned log=$log")
        val state: VideoPlayerState = player.stateFlow.value
        assertTrue(state != VideoPlayerState.Preparing, "seed $seed: stuck in Preparing log=$log")

        val current: ExoPlayer? = exo
        if (!released && current != null) {
            if (state.isPlayable) {
                assertEquals(1, current.mediaItemCount, "seed $seed: playable state with no source in the ExoPlayer, $state log=$log")
            } else {
                assertEquals(0, current.mediaItemCount, "seed $seed: $state but a source is still in the ExoPlayer log=$log")
                assertTrue(!current.isPlaying, "seed $seed: $state but the ExoPlayer plays log=$log")
            }
        }

        player.release()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50L)) }
        if (current != null) assertTrue(current.isReleased, "seed $seed: the ExoPlayer outlived the player log=$log")
        assertEquals(0L, engine.positionMs(), "seed $seed: position after release")
        assertNotNull(player.stateFlow.value)
    }

    private companion object {
        const val SEEDS: Int = 40
        const val OPS: Int = 45
    }
}
