package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The round-four contract changes (late lengths, the end of the source, the initial rate, the lenient
 * unknown-length skip) mixed with rotation (collectors that come and go), re-initialisation after a
 * player that was never released, and callbacks in odd orders. Virtual time only; every loop is capped.
 */
class AudioPlayerRoundFiveMixTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")
    private val other: AudioSource = AudioSource.File("/a/c.mp3")

    private fun TestScope.newPlayer(engine: RecordingPlaybackEngine, config: AudioPlayerConfig = AudioPlayerConfig()): AudioPlayer =
        createAudioPlayer(engine, config, StandardTestDispatcher(testScheduler))

    @Test
    fun `a late length survives a rotation and a skip to the end and completion and a replay and the next source starts unknown again`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.position = 30_000L
            // Rotation: the screen collecting the state is replaced, over and over, while polling goes on.
            repeat(20) {
                val seen: PlayerState = player.stateFlow.value
                assertIs<PlayerState.Playing>(seen)
                advanceTimeBy(100L)
                runCurrent()
            }
            engine.duration = 60_000L
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(60_000L, (player.stateFlow.value as PlayerState.Playing).duration)

            player.seekForward(Long.MAX_VALUE)
            assertEquals(60_000L, engine.position, "a skip by Long.MAX_VALUE with a known length lands on the end")

            engine.listener!!.onCompleted()
            runCurrent()
            assertEquals(PlayerState.Completed(60_000L), player.stateFlow.value)

            player.replay()
            assertEquals(0L, engine.position)
            assertIs<PlayerState.Playing>(player.stateFlow.value)

            player.unload()
            engine.duration = 0L
            player.prepare(other)
            assertEquals(PlayerState.Ready(0L), player.stateFlow.value, "the length of the previous source leaked into the next one")
            player.play()
            assertEquals(0L, (player.stateFlow.value as PlayerState.Playing).duration)
        } finally {
            player.release()
        }
    }

    @Test
    fun `play from every way of standing at the end starts over and from far inside an unknown length does not`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)

            // 1. Completed.
            player.play()
            engine.listener!!.onCompleted(); runCurrent()
            player.play()
            assertEquals(0L, engine.position, "play from Completed")
            player.pause()

            // 2. Paused after a seek onto the end.
            player.seekTo(10_000L)
            assertIs<PlayerState.Paused>(player.stateFlow.value)
            player.play()
            assertEquals(0L, engine.position, "play after seeking onto the end")
            player.pause()

            // 3. Paused by a pause that landed on the last millisecond.
            player.play()
            engine.position = 10_000L
            player.pause()
            player.play()
            assertEquals(0L, engine.position, "play after a pause on the last millisecond")
            player.pause()

            // 4. A pause one millisecond before the end resumes from there.
            engine.position = 9_999L
            player.pause()
            player.play()
            assertEquals(9_999L, engine.position, "play one millisecond before the end must resume")
            player.pause()

            // 5. Unknown length: far inside, never rewound.
            player.unload()
            engine.duration = 0L
            player.prepare(other)
            player.play()
            engine.position = 500_000L
            player.pause()
            player.play()
            assertEquals(500_000L, engine.position, "play inside a source of unknown length must resume")
        } finally {
            player.release()
        }
    }

    @Test
    fun `the initial rate and every later request and the rate handed to the engine stay inside the configured range`() = runTest {
        val configs: List<AudioPlayerConfig> = listOf(
            AudioPlayerConfig(minPlaybackSpeed = 1.5f, maxPlaybackSpeed = 2f),
            AudioPlayerConfig(minPlaybackSpeed = 0.25f, maxPlaybackSpeed = 0.5f),
            AudioPlayerConfig(minPlaybackSpeed = 3f, maxPlaybackSpeed = 3f),
            AudioPlayerConfig(minPlaybackSpeed = Float.MIN_VALUE, maxPlaybackSpeed = Float.MAX_VALUE),
        )
        val requests: List<Float> = listOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -0f, -1f, Float.MIN_VALUE,
            Float.MAX_VALUE, -Float.MAX_VALUE, 1f, 2.5f,
        )
        val random = Random(5)
        for (config in configs) {
            val engine = RecordingPlaybackEngine()
            val player: AudioPlayer = newPlayer(engine, config)
            try {
                fun inRange(label: String, speed: Float) {
                    assertTrue(
                        speed.isFinite() && speed >= config.minPlaybackSpeed && speed <= config.maxPlaybackSpeed,
                        "$label $speed outside ${config.minPlaybackSpeed}..${config.maxPlaybackSpeed}",
                    )
                }
                inRange("initial rate", player.playbackSpeed)
                player.prepare(source)
                inRange("rate applied at Ready", engine.appliedSpeed)
                player.play()
                inRange("rate applied at play", engine.appliedSpeed)
                repeat(60) { step: Int ->
                    player.setPlaybackSpeed(requests[random.nextInt(requests.size)])
                    inRange("rate after request $step", player.playbackSpeed)
                    inRange("engine rate after request $step", engine.appliedSpeed)
                    when (random.nextInt(5)) {
                        0 -> player.pause()
                        1 -> player.play()
                        2 -> player.stop()
                        3 -> { player.unload(); player.prepare(source) }
                        else -> player.replay()
                    }
                    inRange("rate after op $step", player.playbackSpeed)
                    inRange("engine rate after op $step", engine.appliedSpeed)
                }
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun `players that were never released leave a later player on a new engine untouched`() = runTest {
        val abandoned: MutableList<Pair<AudioPlayer, RecordingPlaybackEngine>> = mutableListOf()
        repeat(15) { round: Int ->
            val engine = RecordingPlaybackEngine(duration = if (round % 3 == 0) 0L else 20_000L)
            val player: AudioPlayer = newPlayer(engine, AudioPlayerConfig(positionUpdateIntervalMs = 10L + round))
            player.prepare(source)
            player.play()
            engine.position = 1_000L * round
            advanceTimeBy(50L); runCurrent()
            abandoned += player to engine // process death: nothing releases it
        }
        val freshEngine = RecordingPlaybackEngine(duration = 5_000L)
        val fresh: AudioPlayer = newPlayer(freshEngine)
        try {
            fresh.prepare(other)
            assertEquals(PlayerState.Ready(5_000L), fresh.stateFlow.value)
            fresh.play()
            freshEngine.position = 2_000L
            advanceTimeBy(500L); runCurrent()
            assertEquals(2_000L, fresh.playbackPositionFlow.value)
            for ((player: AudioPlayer, engine: RecordingPlaybackEngine) in abandoned) {
                // Each abandoned player kept to its own engine and its own state.
                assertEquals(source, engine.loadedSources.single())
                assertEquals(0, engine.releaseCount)
                assertIs<PlayerState.Playing>(player.stateFlow.value)
            }
            assertEquals(listOf<AudioSource>(other), freshEngine.loadedSources)
        } finally {
            fresh.release()
            for ((player: AudioPlayer, engine: RecordingPlaybackEngine) in abandoned) {
                player.release()
                assertEquals(1, engine.releaseCount)
            }
        }
        assertEquals(0, freshEngine.releaseCount - 1)
    }
}
