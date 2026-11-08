package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Seeded random operation sequences with extreme arguments (every Long and Float edge, a length that
 * flips between unknown and known and huge, completions and failures at any moment, rare releases),
 * against the invariants the documentation states: nothing throws, nothing negative or NaN is ever
 * published, every position handed to the engine is non-negative, the rate stays inside the range.
 * Virtual time; each run is capped at a fixed number of steps.
 */
class AudioPlayerExtremeInputStormTest {

    private val longs: List<Long> = listOf(
        0L, 1L, -1L, 5_000L, -5_000L, 9_999L, 10_000L, 10_001L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L,
        4_294_967_296L + 100L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
    )
    private val floats: List<Float> = listOf(
        Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f, -1f, 0.1f, 1f, 2f, 5f,
        Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE,
    )
    private val durations: List<Long> = listOf(0L, 0L, 1L, 10_000L, 60_000L, Int.MAX_VALUE.toLong())
    private val sources: List<AudioSource> = listOf(AudioSource.File("/a/b.mp3"), AudioSource.Remote("https://e.test/c.mp3"))

    @Test
    fun `extreme arguments in random orders never throw and never publish a negative or non-finite value`() {
        for (seed in listOf(11, 12, 13, 14, 15, 16)) runStorm(seed)
    }

    private fun runStorm(seed: Int) = runTest {
        val random = Random(seed)
        val engine = RecordingPlaybackEngine(duration = durations[random.nextInt(durations.size)])
        val config = if (seed % 2 == 0) AudioPlayerConfig() else AudioPlayerConfig(minPlaybackSpeed = 1.5f, maxPlaybackSpeed = 2f, positionUpdateIntervalMs = 7L)
        val player: AudioPlayer = createAudioPlayer(engine, config, StandardTestDispatcher(testScheduler))
        engine.appliedSpeed = player.playbackSpeed // the engine has not been asked for a rate yet
        val log: MutableList<String> = mutableListOf()
        fun check(step: Int) {
            val state: PlayerState = player.stateFlow.value
            val where = "seed $seed step $step state $state last ${log.takeLast(4)}"
            val duration: Long? = state.duration
            if (duration != null) assertTrue(duration >= 0L, "negative duration: $where")
            state.playbackPosition?.let { assertTrue(it >= 0L, "negative position: $where") }
            val progress: Float = state.progress
            assertTrue(progress.isFinite() && progress in 0f..1f, "progress $progress: $where")
            assertTrue(player.playbackPositionFlow.value >= 0L, "negative position flow: $where")
            assertTrue(
                player.playbackSpeed.isFinite() && player.playbackSpeed >= config.minPlaybackSpeed && player.playbackSpeed <= config.maxPlaybackSpeed,
                "rate ${player.playbackSpeed}: $where",
            )
            assertTrue(engine.seekTargets.all { it >= 0L }, "negative seek target ${engine.seekTargets.filter { it < 0L }}: $where")
            assertTrue(
                engine.appliedSpeed.isFinite() && engine.appliedSpeed >= config.minPlaybackSpeed && engine.appliedSpeed <= config.maxPlaybackSpeed,
                "engine rate ${engine.appliedSpeed}: $where",
            )
        }
        try {
            repeat(2_500) { step: Int ->
                val op: Int = random.nextInt(if (step > 2_000) 22 else 21)
                try {
                    when (op) {
                        0, 1 -> { log += "prepare"; engine.loadFailure = null; player.prepare(sources[random.nextInt(sources.size)]) }
                        2 -> { log += "prepare-fails"; engine.loadFailure = IllegalStateException("boom"); player.prepare(sources[0]); engine.loadFailure = null }
                        3, 4 -> { log += "play"; player.play() }
                        5 -> { log += "pause"; player.pause() }
                        6 -> { log += "stop"; player.stop() }
                        7, 8 -> { val a = longs[random.nextInt(longs.size)]; log += "seekTo($a)"; player.seekTo(a) }
                        9, 10 -> { val a = longs[random.nextInt(longs.size)]; log += "seekForward($a)"; player.seekForward(a) }
                        11, 12 -> { val a = longs[random.nextInt(longs.size)]; log += "seekBackward($a)"; player.seekBackward(a) }
                        13 -> { log += "replay"; player.replay() }
                        14, 15 -> { val a = floats[random.nextInt(floats.size)]; log += "speed($a)"; player.setPlaybackSpeed(a) }
                        16 -> { log += "unload"; player.unload() }
                        17 -> { log += "completed"; engine.listener?.onCompleted(); runCurrent() }
                        18 -> { log += "failed"; engine.listener?.onFailed(IllegalStateException("late")); runCurrent() }
                        19 -> { val d = durations[random.nextInt(durations.size)]; log += "duration($d)"; engine.duration = d }
                        20 -> { val p = longs[random.nextInt(longs.size)].coerceAtLeast(0L); log += "position($p)"; engine.position = p }
                        else -> { log += "release"; player.release() }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (thrown: Throwable) {
                    fail("seed $seed step $step ${log.last()} threw $thrown")
                }
                if (random.nextInt(3) == 0) {
                    advanceTimeBy(random.nextLong(0L, 120L))
                    runCurrent()
                }
                check(step)
            }
        } finally {
            player.release()
        }
    }
}
