package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, Q3 (engine swap on the same script): the consumer script of `ConsumerFacadeR15ModelTest` (kmptoolkit-video-player-javafx)
 * and `VlcjConsumerFacadeR15AdversarialTest` (kmptoolkit-video-player-vlcj), with the same literal, over the scripted engine of this
 * module. The documented expected trace comes from the core guide and API reference ("Settings", "Transport", "Lifecycle"), not
 * from any engine; the one divergence of the three engines is the JavaFX key-frame snap, a documented platform note
 * (javafx 05-platform-notes, "Seeking"), in the javafx class. The fake does not move the playhead or end a source on its own, so
 * "play to the end" is `completePlayback()`.
 */
class ConsumerScriptDifferentialD2R15Test {

    private val engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = 3_000L)
    private val player: VideoPlayer = createVideoPlayer(
        engine = engine,
        config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        coroutineContext = Dispatchers.Unconfined,
    )

    private fun prepare(): VideoPlayerState {
        runBlocking { withTimeout(BOUND_MS) { player.prepare(VideoSource.Remote("https://example.test/a.mp4")) } }
        return player.stateFlow.value
    }

    private fun VideoPlayer.line(label: String): String {
        val state: String = when (val s: VideoPlayerState = stateFlow.value) {
            VideoPlayerState.Idle -> "Idle"
            VideoPlayerState.Preparing -> "Preparing"
            is VideoPlayerState.Ready -> "Ready(${s.duration})"
            is VideoPlayerState.Playing -> "Playing(${s.duration})"
            is VideoPlayerState.Paused -> "Paused(${s.duration},${s.currentPosition})"
            is VideoPlayerState.Completed -> "Completed(${s.duration})"
            is VideoPlayerState.Error -> "Error(${s.cause::class.simpleName})"
        }
        return "$label | $state pos=${playbackPositionFlow.value} speed=${playbackSpeedFlow.value} vol=${volumeFlow.value} " +
            "muted=${isMutedFlow.value} repeat=${repeatModeFlow.value}"
    }

    @Test
    fun `the consumer script gives the documented trace on the scripted engine`() {
        val trace: MutableList<String> = mutableListOf()
        fun note(label: String) {
            trace += player.line(label)
        }
        note("initial")
        player.play(); player.pause(); player.stop(); player.seekTo(500L); player.seekForward(1_000L)
        player.seekBackward(1_000L); player.replay()
        note("transport before prepare is ignored")
        player.setPlaybackSpeed(2f); player.setVolume(0.5f); player.setMuted(true); player.setRepeatMode(RepeatMode.One)
        note("settings before prepare show at once")
        prepare()
        note("prepared")
        player.setPlaybackSpeed(Float.NaN); note("speed NaN is ignored")
        player.setPlaybackSpeed(100f); note("speed 100")
        player.setPlaybackSpeed(0f); note("speed 0")
        player.setPlaybackSpeed(-5f); note("speed -5")
        player.setPlaybackSpeed(Float.POSITIVE_INFINITY); note("speed +Inf")
        player.setPlaybackSpeed(Float.NEGATIVE_INFINITY); note("speed -Inf")
        player.setPlaybackSpeed(1f); note("speed 1")
        player.setVolume(5f); note("volume 5")
        player.setVolume(-1f); note("volume -1")
        player.setVolume(Float.NaN); note("volume NaN is ignored")
        player.setVolume(0.5f); note("volume 0.5")
        player.seekTo(Long.MAX_VALUE); note("seek MAX in Ready")
        player.seekTo(-5L); note("seek -5 in Ready")
        player.seekTo(1_200L); note("seek 1200 in Ready")
        player.play(); note("play")
        player.pause(); note("pause")
        player.seekTo(1_000L); note("seek 1000 paused")
        player.seekForward(10_000L); note("seekForward 10000 paused")
        player.seekBackward(Long.MAX_VALUE); note("seekBackward MAX paused")
        player.setRepeatMode(RepeatMode.Off)
        player.play(); engine.completePlayback(); note("play to the end with repeat off")
        player.pause(); note("pause in Completed")
        player.seekTo(1_000L); note("seek 1000 in Completed")
        player.play(); engine.completePlayback(); note("play to the end again")
        player.stop(); note("stop")
        player.replay(); note("replay")
        engine.completePlayback(); note("replay to the end")
        player.play(); note("play from Completed")
        player.unload(); note("unload keeps the settings")
        prepare(); note("prepare again")
        player.release(); note("release")
        player.release(); note("release twice")
        player.play(); player.seekTo(10L); note("transport after release is ignored")
        prepare(); note("prepare after release")
        player.setVolume(0.3f); note("volume after release shows at once")
        assertEquals(EXPECTED_SCRIPT, trace, "the consumer script, trace per documented sentence")
    }


    @Test
    fun `a play with the playhead on the end starts over from zero from Ready and from Paused on the scripted engine`() {
        prepare()
        player.seekTo(Long.MAX_VALUE)
        assertEquals(3_000L, player.playbackPositionFlow.value)
        player.play()
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
        assertEquals(0L, player.playbackPositionFlow.value, "play from a Ready player on the end")
        player.pause()
        player.seekTo(Long.MAX_VALUE)
        assertEquals(VideoPlayerState.Paused(3_000L, 3_000L), player.stateFlow.value)
        player.play()
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
        assertEquals(0L, player.playbackPositionFlow.value, "play from a Paused player on the end")
        assertEquals(0L, engine.positionMs, "the engine was asked to start over")
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L

        val EXPECTED_SCRIPT: List<String> = listOf(
            "initial | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "transport before prepare is ignored | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "settings before prepare show at once | Idle pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "prepared | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed NaN is ignored | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed 100 | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed 0 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed -5 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed +Inf | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed -Inf | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed 1 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "volume 5 | Ready(3000) pos=0 speed=1.0 vol=1.0 muted=true repeat=One",
            "volume -1 | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume NaN is ignored | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume 0.5 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek MAX in Ready | Ready(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek -5 in Ready | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1200 in Ready | Ready(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "play | Playing(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "pause | Paused(3000,1200) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1000 paused | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekForward 10000 paused | Paused(3000,3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekBackward MAX paused | Paused(3000,0) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "play to the end with repeat off | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "pause in Completed | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "seek 1000 in Completed | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play to the end again | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "stop | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay to the end | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play from Completed | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "unload keeps the settings | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare again | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release twice | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "transport after release is ignored | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare after release | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "volume after release shows at once | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.3 muted=true repeat=Off",
        )
    }
}
