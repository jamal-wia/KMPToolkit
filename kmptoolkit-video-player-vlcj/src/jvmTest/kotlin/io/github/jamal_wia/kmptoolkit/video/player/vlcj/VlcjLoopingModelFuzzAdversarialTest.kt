package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The output-model fuzz again, with a looping source: at the end VLCJ's own repeat handler replays the
 * media from 0, which is a start from rest (no running input until the next Playing). A seek the app
 * makes in that gap must be applied once the restart has output; a seek made before the end is
 * undone by the replay. Pause is only issued while an input is running (the model drops it
 * otherwise, which is a separate question). Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLoopingModelFuzzAdversarialTest {

    private val fakes: MutableList<FakeVlcRuntime> = mutableListOf()
    private var judged: Int = 0

    @AfterTest
    fun tearDown() {
        fakes.forEach { it.shutdown() }
    }

    private class ModelPlayer(private val inner: VlcNativePlayer) : VlcNativePlayer by inner {
        @Volatile var alive: Boolean = false
        @Volatile var playhead: Long = 0L
        @Volatile var playRequested: Boolean = false

        override fun play() {
            if (!alive) {
                playhead = 0L
                playRequested = true
            }
            inner.play()
        }

        override fun setTime(timeMs: Long) {
            if (alive) playhead = timeMs
            inner.setTime(timeMs)
        }
    }

    @Test
    fun `no random order of start pause seek and loop restart loses a seek`() {
        for (seed: Int in 1..400) {
            runBlocking { runOne(seed) }
        }
        assertTrue(judged >= 100, "only $judged of 400 runs ended in a state the model can judge")
    }

    private suspend fun runOne(seed: Int) {
        val random = Random(seed)
        lateinit var model: ModelPlayer
        val fake = FakeVlcRuntime().also { fakes += it }
        val wrapped = object : VlcRuntime by fake {
            override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer =
                ModelPlayer(fake.newPlayer(callbacks)).also { model = it }
        }
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { wrapped })
        engine.setLooping(true)
        try {
            withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
            val player: FakeVlcPlayer = fake.player
            var started = false
            var seekTarget: Long? = null
            var seekLive = false
            val trace = StringBuilder()
            repeat(60) { step: Int ->
                val op: Int = random.nextInt(8)
                trace.append(step).append(':').append(op).append(' ')
                when (op) {
                    0 -> {
                        engine.start()
                        started = true
                    }
                    1 -> if (started && model.alive) engine.pause()
                    2, 3 -> {
                        val target: Long = random.nextLong(100L, 1_900L)
                        engine.seekTo(target)
                        seekTarget = target
                        seekLive = started && model.alive
                    }
                    4, 5 -> {
                        if (started && (model.playRequested || model.alive)) {
                            model.alive = true
                            model.playRequested = false
                            player.fire { playing() }
                            fake.drainTasks()
                            if (seekTarget != null) seekLive = true
                        }
                    }
                    6 -> {
                        if (started && model.alive) {
                            // The loop point: the input ends, VLCJ replays from 0, the engine hears finished.
                            model.alive = false
                            model.playhead = 0L
                            model.playRequested = true
                            player.fire { finished() }
                            fake.drainTasks()
                            if (seekLive) seekTarget = 0L
                            seekLive = false
                        }
                    }
                    else -> fake.drainTasks()
                }
            }
            fake.drainTasks()
            if (started) {
                if (!model.alive) {
                    model.alive = true
                    model.playRequested = false
                    player.fire { playing() }
                    fake.drainTasks()
                }
                judged++
                val expected: Long = seekTarget ?: 0L
                assertEquals(expected, model.playhead, "seed $seed: a seek was lost or sent into a dead input; steps $trace; calls ${player.calls}")
            }
        } finally {
            engine.dispose()
        }
    }
}
