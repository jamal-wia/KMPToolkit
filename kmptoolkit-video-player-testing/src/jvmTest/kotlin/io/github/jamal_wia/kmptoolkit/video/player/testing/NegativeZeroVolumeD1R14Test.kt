package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 14, F1 (low): `setVolume` clamps into `0f..1f` and `volumeFlow` is "Output volume in `0f..1f`". `-0f` lies in that
 * range numerically, but a StateFlow conflates by `equals` and `Float.equals` compares bits, so a signed zero published as it
 * came emitted twice for one numeric value and printed as `-0.0`. The player normalises it to `0f`.
 */
class NegativeZeroVolumeD1R14Test {
    private fun rig(): Pair<VideoPlayer, ScriptedEngineD1R11> {
        val engine = ScriptedEngineD1R11()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(TestCoroutineScheduler()))
        runBlocking { player.prepare(VideoSource.File("/x.mp4")) }
        return player to engine
    }

    @Test
    fun `a volume of negative zero is shown as zero`() {
        // "Clamps into 0f..1f": the flow of a player muted by its own volume must not show a signed zero, which prints
        // as -0.0 and is not equal to 0f as a boxed value.
        val (player, engine) = rig()
        player.setVolume(-0f)
        assertEquals(0, player.volumeFlow.value.toRawBits(), "volumeFlow holds ${player.volumeFlow.value}")
        assertEquals(0, engine.volumes.last().toRawBits(), "the platform heard ${engine.volumes.last()}")
        player.release()
    }

    @Test
    fun `negative zero then zero emits once`() {
        val (player, _) = rig()
        val seen: MutableList<Int> = mutableListOf()
        runBlocking {
            val collector: Job = CoroutineScope(Dispatchers.Unconfined).launch {
                player.volumeFlow.collect { seen += it.toRawBits() }
            }
            player.setVolume(-0f)
            player.setVolume(0f)
            collector.cancelAndJoin()
        }
        // The initial 1f, then one zero: the second call is the same value and a StateFlow does not emit it again.
        assertEquals(listOf(1f.toRawBits(), 0), seen, "volumeFlow emitted these raw bits: $seen")
        player.release()
    }
}
