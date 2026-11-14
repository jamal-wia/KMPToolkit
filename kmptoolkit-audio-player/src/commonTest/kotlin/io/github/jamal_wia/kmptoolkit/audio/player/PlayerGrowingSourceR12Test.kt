package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 12, Q1 (player half): "a file that grows while it is read", the shape of a recording that is played back
 * while it is still being written, or a progressive download. The engine's length starts unknown (0), grows in
 * steps, and now and then reads 0 again for a moment. Contract (03 and 04 of the player docs, and the tests of the
 * length-flap round): a positive platform length replaces a smaller one and a 0 never overwrites a known one, so the
 * published length never decreases and never exceeds what the engine reported; an absolute seek clamps to the
 * length the engine reports at that moment, not to the stale one the state carried; a playing or paused position
 * never exceeds the published length; completion publishes the length of the end. Seeded, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerGrowingSourceR12Test {

    private val source: AudioSource = AudioSource.File("/r12/growing.m4a")

    @Test
    fun `a growing source never makes the player claim a shorter or an unreported length and seeks clamp to the live length`() {
        for (seed: Long in longArrayOf(7L, 71L, 717L, 7171L)) {
            runTest {
                val random = Random(seed)
                val engine = FakePlaybackEngine(durationMs = 0L)
                val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
                try {
                    player.prepare(source)
                    var latest = 0L
                    var lastShown = 0L
                    repeat(160) { step: Int ->
                        val label = "seed $seed step $step"
                        val before: PlayerState = player.stateFlow.value
                        when (random.nextInt(11)) {
                            0, 1 -> {
                                latest += random.nextInt(1, 4_000)
                                engine.durationMs = latest
                            }
                            2 -> engine.durationMs = 0L
                            3 -> {
                                val target: Long = random.nextLong(-3_000L, latest + 6_000L)
                                player.seekTo(target)
                                if (before.isPlayable && engine.durationMs > 0L) {
                                    val expected: Long = target.coerceIn(0L, engine.durationMs)
                                    assertEquals(expected, engine.positionMs, "$label: engine told ${engine.positionMs} for $target, live length ${engine.durationMs}")
                                    assertEquals(expected, player.playbackPositionFlow.value, "$label: position flow after a seek to $target")
                                }
                            }
                            4 -> player.seekForward(random.nextLong(0L, 5_000L))
                            5 -> player.seekBackward(random.nextLong(0L, 5_000L))
                            6 -> player.play()
                            7 -> player.pause()
                            8 -> if (random.nextBoolean()) player.stop() else player.replay()
                            9 -> if (engine.isPlaying) engine.advancePositionBy(random.nextLong(0L, 3_000L))
                            else -> if (player.stateFlow.value is PlayerState.Playing) {
                                engine.completePlayback()
                            }
                        }
                        advanceTimeBy(100L * random.nextInt(0, 4))
                        runCurrent()

                        val state: PlayerState = player.stateFlow.value
                        val shown: Long? = state.duration
                        if (shown != null) {
                            assertTrue(shown >= lastShown, "$label: the published length went back from $lastShown to $shown in $state")
                            assertTrue(shown <= latest, "$label: the published length $shown is longer than anything the engine reported ($latest)")
                            lastShown = shown
                        }
                        if (state is PlayerState.Playing && state.duration > 0L) {
                            assertTrue(state.currentPosition <= state.duration, "$label: position beyond the length in $state")
                        }
                        if (state is PlayerState.Paused && state.duration > 0L) {
                            assertTrue(state.currentPosition <= state.duration, "$label: position beyond the length in $state")
                        }
                    }
                } finally {
                    player.release()
                }
            }
        }
    }

    @Test
    fun `a source that grew after the load is picked up by the first seek and by completion`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 4_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            assertEquals(PlayerState.Ready(4_000L), player.stateFlow.value)
            engine.durationMs = 9_000L

            player.seekTo(8_000L)
            assertEquals(PlayerState.Ready(9_000L), player.stateFlow.value)
            assertEquals(8_000L, engine.positionMs, "the seek was clamped to the stale length")

            player.play()
            engine.durationMs = 12_000L
            engine.completePlayback()
            runCurrent()
            assertEquals(PlayerState.Completed(12_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }
}
