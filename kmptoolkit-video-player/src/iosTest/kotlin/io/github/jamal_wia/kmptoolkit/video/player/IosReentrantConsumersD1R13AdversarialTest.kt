package io.github.jamal_wia.kmptoolkit.video.player

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.AVFoundation.volume

/**
 * D1 round 13, Q1 on iOS: the REAL AVPlayer engine behind the shared player, driven by consumers whose collectors call the
 * player from inside flow updates (a `Main.immediate` collector, what `viewModelScope` is). A pause then a seek from the first
 * `Playing`, a play from `Idle` after an unload, a mute from `Preparing`, and a seeded set of random reaction chains. After the
 * dust settles the AVPlayer must agree with the flows: it plays (rate above 0) iff the state is `Playing`, holds no item
 * when `Idle`, carries the folded volume, and (while playing) the speed.
 */
@OptIn(ToolkitInternalApi::class)
class IosReentrantConsumersD1R13AdversarialTest {

    private val movie: String by lazy { writeTestMovie("kmptoolkit-video-player-ios-d1r13", frameCount = LONG_MOVIE_FRAMES) }

    private fun newPlayer(): VideoPlayer = createVideoPlayer(
        config = VideoPlayerConfig(positionUpdateIntervalMs = 50L),
        managesAudioSession = false,
    )

    private fun VideoPlayer.avPlayer(): AVPlayer = assertNotNull(avPlayerOrNull(), "no AVPlayer behind the player")

    private fun settle() {
        pumpMainLoopUntil(0.15) { false }
    }

    private fun judge(label: String, player: VideoPlayer) {
        val state: VideoPlayerState = player.stateFlow.value
        val av: AVPlayer = player.avPlayer()
        val playing: Boolean = state is VideoPlayerState.Playing
        if (state is VideoPlayerState.Preparing) fail("$label: left in Preparing")
        if (state !is VideoPlayerState.Completed && state !is VideoPlayerState.Error) {
            assertEquals(playing, av.rate > 0f, "$label: state $state but the AVPlayer rate is ${av.rate}")
        }
        if (state == VideoPlayerState.Idle) assertNull(av.currentItem, "$label: Idle but the AVPlayer holds an item")
        val playable: Boolean = playing || state is VideoPlayerState.Ready || state is VideoPlayerState.Paused
        if (playable) {
            val effective: Float = if (player.isMutedFlow.value) 0f else player.volumeFlow.value
            assertTrue(abs(av.volume - effective) < 0.001f, "$label: state $state, AVPlayer volume ${av.volume} but the flows say $effective")
            if (playing) {
                assertTrue(abs(av.rate - player.playbackSpeedFlow.value) < 0.01f, "$label: AVPlayer rate ${av.rate} but the speed flow says ${player.playbackSpeedFlow.value}")
            }
        }
    }

    @Test
    fun `a collector that pauses and seeks from the first Playing leaves a paused AVPlayer at the target`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
            var fired = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect {
                    if (it is VideoPlayerState.Playing && !fired) {
                        fired = true
                        player.pause()
                        player.seekTo(2_000L)
                    }
                }
            }
            player.play()
            assertTrue(awaitCondition(2_000) { fired }, "the collector ran")
            settle()
            val state: VideoPlayerState.Paused = kotlin.test.assertIs(player.stateFlow.value)
            assertTrue(abs(state.currentPosition - 2_000L) <= 100L, "paused at the target: $state")
            judge("pause then seek", player)
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun `a collector that plays from Idle after an unload leaves an AVPlayer with no item and no rate`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
            var fired = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.drop(1).collect {
                    if (it == VideoPlayerState.Idle && !fired) {
                        fired = true
                        player.play()
                        player.replay()
                        player.setMuted(true)
                    }
                }
            }
            player.unload()
            settle()
            assertTrue(fired, "the collector ran")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            judge("play from Idle", player)
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun `a collector that mutes and slows from Preparing leaves the loaded AVPlayer with the settings of the flows`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
            var fired = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect {
                    if (it is VideoPlayerState.Preparing && !fired) {
                        fired = true
                        player.setMuted(true)
                        player.setPlaybackSpeed(2f)
                        player.setRepeatMode(RepeatMode.One)
                        player.setVolume(0.5f)
                    }
                }
            }
            player.prepare(VideoSource.File(path))
            assertTrue(fired, "the collector ran")
            judge("settings from Preparing", player)
            assertEquals(0f, player.avPlayer().volume, "muted")
            player.play()
            settle()
            judge("play after the settings", player)
            scope.cancel()
            player.release()
        }
    }

    private enum class Op { Play, Pause, Stop, Seek, Replay, SetRepeat, SetSpeed, SetVolume, SetMuted, Unload, Prepare }

    private enum class Watched { State, Position, Buffered, Buffering, Size }

    @Test
    fun `seeded random reaction chains over the real AVPlayer leave it in agreement with the flows`() {
        val path: String = movie
        val base = 13_013L
        runOnMainLoop(timeoutSeconds = 120.0) {
            for (seed in base until base + SCENARIOS) {
                val random = Random(seed)
                val player: VideoPlayer = newPlayer()
                val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
                val prepares: MutableList<Job> = mutableListOf()
                val trace: MutableList<String> = mutableListOf()
                var armed = true
                var depth = 0
                val escaped: MutableList<Throwable> = mutableListOf()
                fun run(op: Op) {
                    trace += "  ".repeat(depth) + op.name
                    when (op) {
                        Op.Play -> player.play()
                        Op.Pause -> player.pause()
                        Op.Stop -> player.stop()
                        Op.Seek -> player.seekTo(random.nextLong(0L, 3_000L))
                        Op.Replay -> player.replay()
                        Op.SetRepeat -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                        Op.SetSpeed -> player.setPlaybackSpeed(0.5f + random.nextInt(4) * 0.5f)
                        Op.SetVolume -> player.setVolume(random.nextInt(5) / 4f)
                        Op.SetMuted -> player.setMuted(random.nextBoolean())
                        Op.Unload -> player.unload()
                        Op.Prepare -> prepares += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                player.prepare(VideoSource.File(path))
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (thrown: Throwable) {
                                escaped += thrown
                            }
                        }
                    }
                }
                try {
                    player.prepare(VideoSource.File(path))
                    if (random.nextBoolean()) player.play()
                    repeat(2 + random.nextInt(2)) {
                        val watched: Watched = Watched.entries[random.nextInt(Watched.entries.size)]
                        val flow: Flow<Any?> = when (watched) {
                            Watched.State -> player.stateFlow
                            Watched.Position -> player.playbackPositionFlow
                            Watched.Buffered -> player.bufferedPositionFlow
                            Watched.Buffering -> player.isBufferingFlow
                            Watched.Size -> player.videoSizeFlow
                        }
                        trace += "collector on $watched"
                        var fires = 0
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            flow.drop(1).collect {
                                if (armed && depth < 3 && fires < 2 && random.nextInt(100) < 80) {
                                    fires++
                                    depth++
                                    try {
                                        run(Op.entries[random.nextInt(Op.entries.size)])
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (thrown: Throwable) {
                                        escaped += thrown
                                    } finally {
                                        depth--
                                    }
                                }
                            }
                        }
                    }
                    repeat(3 + random.nextInt(4)) {
                        if (random.nextInt(100) < 35) pumpMainLoopUntil(0.03) { false } else run(Op.entries[random.nextInt(Op.entries.size)])
                    }
                    armed = false
                    awaitCondition(10_000) { prepares.none { it.isActive } }
                    settle()
                    if (escaped.isNotEmpty()) fail("seed $seed: an exception escaped: $escaped")
                    try {
                        judge("seed $seed", player)
                    } catch (red: Throwable) {
                        throw AssertionError("${red.message}\ntrace:\n" + trace.joinToString("\n"), red)
                    }
                } finally {
                    scope.cancel()
                    player.release()
                }
            }
        }
    }

    private companion object {
        const val LONG_MOVIE_FRAMES: Int = 300
        const val SCENARIOS: Int = 25
    }
}
