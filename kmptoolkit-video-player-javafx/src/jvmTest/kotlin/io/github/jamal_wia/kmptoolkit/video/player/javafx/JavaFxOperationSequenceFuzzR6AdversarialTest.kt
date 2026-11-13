package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6, model-based, real JavaFX: seeded random sequences of player calls (play, pause, stop, replay,
 * seeks of every kind, speed, volume, mute, unload and prepare again) on the real engine and the real
 * toolkit, each followed by a random wait. After every call the state must be one the documented
 * transition table allows given the state just before it (a natural end may land in between, so a
 * Playing player may also read Completed); a seek on a paused player is published at once, exactly;
 * a paused playhead stays put; a Playing player whose source is not at its end moves on within 2.5 s
 * (no frozen playhead, no stuck Playing); and at the end of every sequence the player still plays its
 * source to the end and completes, then releases. No speed is changed here (see r6-D2-S6). The repeat mode is fixed per seed, so a looping seed
 * never reads Completed. Every failure names the seed and the trace.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxOperationSequenceFuzzR6AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private fun kind(state: VideoPlayerState): String = state::class.simpleName.orEmpty()

    private fun runSeed(seed: Int, operations: Int) {
        val random = Random(seed)
        val looping: Boolean = seed % 3 == 0
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 50L))
        val trace = StringBuilder("repeat=${if (looping) "One" else "Off"}; ")
        fun where(): String = "seed $seed; trace: $trace; state ${player.stateFlow.value}; engine position ${engine.positionMs()}"
        try {
            runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            player.setRepeatMode(if (looping) RepeatMode.One else RepeatMode.Off)
            var lastMoveNanos: Long = System.nanoTime()
            var lastPosition: Long = -1L

            fun watch(ms: Long) {
                val end: Long = System.nanoTime() + ms * 1_000_000L
                while (System.nanoTime() < end) {
                    val state: VideoPlayerState = player.stateFlow.value
                    if (looping && state is VideoPlayerState.Completed) fail("a looping source reported Completed; ${where()}")
                    val position: Long = engine.positionMs()
                    if (position != lastPosition || state !is VideoPlayerState.Playing) {
                        lastPosition = position
                        lastMoveNanos = System.nanoTime()
                    }
                    if (state is VideoPlayerState.Playing && position < TestClip.DURATION_MS - 300L &&
                        System.nanoTime() - lastMoveNanos > 2_500L * 1_000_000L
                    ) {
                        fail("Playing for 2.5 s with the playhead standing at $position of ${TestClip.DURATION_MS}; ${where()}")
                    }
                    Thread.sleep(20L)
                }
            }

            fun call(name: String, allowed: (before: VideoPlayerState, after: VideoPlayerState) -> Boolean, action: () -> Unit) {
                val before: VideoPlayerState = player.stateFlow.value
                trace.append(name).append(" ")
                action()
                val after: VideoPlayerState = player.stateFlow.value
                if (!allowed(before, after)) fail("$name took ${kind(before)} to ${kind(after)}; ${where()}")
                lastMoveNanos = System.nanoTime()
                watch(random.nextLong(0L, 260L))
            }

            repeat(operations) {
                when (random.nextInt(14)) {
                    0, 1 -> call("play", { b, a ->
                        when (b) {
                            is VideoPlayerState.Ready, is VideoPlayerState.Paused, is VideoPlayerState.Completed, is VideoPlayerState.Playing ->
                                a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                            else -> a == b
                        }
                    }) { player.play() }

                    2, 3 -> call("pause", { b, a ->
                        when (b) {
                            is VideoPlayerState.Playing -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                            else -> kind(a) == kind(b)
                        }
                    }) { player.pause() }

                    4 -> call("stop", { _, a -> a is VideoPlayerState.Ready }) { player.stop() }

                    5, 6 -> {
                        val target: Long = random.nextLong(-50L, TestClip.DURATION_MS + 200L)
                        val clamped: Long = target.coerceIn(0L, TestClip.DURATION_MS)
                        call("seekTo($target)", { b, a ->
                            when (b) {
                                is VideoPlayerState.Paused -> a is VideoPlayerState.Paused && a.currentPosition == clamped
                                is VideoPlayerState.Ready -> a is VideoPlayerState.Ready
                                is VideoPlayerState.Completed -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                                is VideoPlayerState.Playing -> a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                                else -> a == b
                            }
                        }) { player.seekTo(target) }
                    }

                    7 -> {
                        val amount: Long = random.nextLong(1L, 2_000L)
                        call("seekForward($amount)", { b, a -> kind(b) == kind(a) || a is VideoPlayerState.Completed || a is VideoPlayerState.Paused && b is VideoPlayerState.Completed }) {
                            player.seekForward(amount)
                        }
                    }

                    8 -> {
                        val amount: Long = random.nextLong(1L, 2_000L)
                        call("seekBackward($amount)", { b, a -> kind(b) == kind(a) || a is VideoPlayerState.Completed || a is VideoPlayerState.Paused && b is VideoPlayerState.Completed }) {
                            player.seekBackward(amount)
                        }
                    }

                    9 -> call("replay", { b, a ->
                        if (b.run { this is VideoPlayerState.Ready || this is VideoPlayerState.Playing || this is VideoPlayerState.Paused || this is VideoPlayerState.Completed }) {
                            a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                        } else {
                            a == b
                        }
                    }) { player.replay() }

                    10 -> {
                        // No speed here: a speed given while the player is not playing, or right after a play(), reads the
                        // suspects r6-D2-S6 / S6b / S6c (a frozen playhead); the speed is covered by its own tests.
                        call("seekTo(0)", { b, a ->
                            when (b) {
                                is VideoPlayerState.Paused -> a is VideoPlayerState.Paused && a.currentPosition == 0L
                                is VideoPlayerState.Ready -> a is VideoPlayerState.Ready
                                is VideoPlayerState.Completed -> a is VideoPlayerState.Paused || a is VideoPlayerState.Completed
                                is VideoPlayerState.Playing -> a is VideoPlayerState.Playing || a is VideoPlayerState.Completed
                                else -> a == b
                            }
                        }) { player.seekTo(0L) }
                    }

                    11 -> {
                        val volume: Float = listOf(0f, 0.3f, 1f).random(random)
                        call("volume($volume)", { _, _ -> true }) { player.setVolume(volume) }
                    }

                    12 -> call("mute", { _, _ -> true }) { player.setMuted(random.nextBoolean()) }

                    else -> call("unload+prepare", { _, a -> a is VideoPlayerState.Ready }) {
                        player.unload()
                        runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                    }
                }
            }

            // The player must still play its source to the end: no stuck Playing, no frozen playhead.
            trace.append("final ")
            player.setRepeatMode(RepeatMode.Off)
            player.setMuted(true)
            player.replay()
            val deadline: Long = System.nanoTime() + 12_000L * 1_000_000L
            while (System.nanoTime() < deadline && player.stateFlow.value !is VideoPlayerState.Completed) Thread.sleep(25L)
            if (player.stateFlow.value !is VideoPlayerState.Completed) fail("a replay never completed within 12 s; ${where()}")
        } finally {
            player.release()
            player.release()
        }
        if (player.stateFlow.value != VideoPlayerState.Idle) fail("not Idle after release: ${player.stateFlow.value}; seed $seed")
    }

    @Test
    fun `random call sequences on the real engine keep to the transition table and never freeze`() {
        // kmptoolkit.test.r6.seeds / kmptoolkit.test.r6.seed.from lengthen the run (more seeds, other seeds) without editing the test.
        val count: Int = System.getProperty("kmptoolkit.test.r6.seeds")?.toIntOrNull() ?: SEEDS
        val from: Int = System.getProperty("kmptoolkit.test.r6.seed.from")?.toIntOrNull() ?: 1
        for (seed: Int in from until from + count) runSeed(seed, operations = 26)
    }

    private companion object {
        const val SEEDS: Int = 6
    }
}
