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
 * Drives the engine with random, valid libvlc event orders against a native player that behaves as
 * the engine documents libvlc: a `setTime` is honoured only while an input is running (between a
 * Playing event and the end of the source), and a play from the end restarts at 0. Whatever the
 * order of start, pause, seek, resume and end, a seek the app made must not be lost: once output
 * runs, the native playhead is where the last seek said.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjOutputModelFuzzAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private var asserted: Int = 0

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    /** The native player's own model: [alive] is an input that accepts a seek. */
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
    fun `no random order of start pause seek and end loses a seek or sends one into a dead input`() {
        for (seed: Int in 1..400) {
            runBlocking { runOne(seed) }
        }
        assertTrue(asserted >= 100, "only $asserted of 400 runs ended in a state the model can judge")
    }

    private suspend fun runOne(seed: Int) {
        val random = Random(seed)
        lateinit var model: ModelPlayer
        val fake = FakeVlcRuntime()
        val wrapped = object : VlcRuntime by fake {
            override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer =
                ModelPlayer(fake.newPlayer(callbacks)).also { model = it }
        }
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { wrapped })
        val listener = RecordingListener()
        val restartOnEnd: Boolean = random.nextBoolean()
        if (restartOnEnd) {
            listener.reaction = { event ->
                if (event == "completed") {
                    engine.seekTo(0L)
                    engine.start()
                }
            }
        }
        engine.setListener(listener)
        try {
            withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
            val player: FakeVlcPlayer = fake.player
            var started = false
            var ended = false
            var seekTarget: Long? = null
            var seekLive = false
            val trace = StringBuilder()
            repeat(60) { step: Int ->
                val op: Int = random.nextInt(8)
                trace.append(step).append(':').append(op).append(' ')
                when (op) {
                    0 -> { // start
                        val fromRest: Boolean = !started || ended
                        engine.start()
                        started = true
                        ended = false
                        // A seek that reached a running input is undone by a restart from the end, which plays from 0.
                        if (fromRest && seekTarget != null && seekLive) seekTarget = null
                    }
                    1 -> engine.pause()
                    2, 3 -> { // seek
                        val target: Long = random.nextLong(100L, 1_900L)
                        engine.seekTo(target)
                        seekTarget = target
                        seekLive = started && !ended && model.alive
                    }
                    4, 5 -> { // libvlc reports Playing for a requested play, or a resume
                        if (started && !ended && (model.playRequested || model.alive)) {
                            model.alive = true
                            model.playRequested = false
                            player.fire { playing() }
                            fake.drainTasks()
                            if (seekTarget != null) seekLive = true // a parked seek has been applied now
                        }
                    }
                    6 -> { // the source ends
                        if (started && !ended && model.alive) {
                            model.alive = false
                            player.fire { finished() }
                            ended = true
                            fake.drainTasks()
                            if (restartOnEnd) {
                                // The listener restarted it: a seek to 0 then a start from the end.
                                ended = false
                                seekTarget = 0L
                                seekLive = false
                            }
                        }
                    }
                    else -> fake.drainTasks()
                }
            }
            fake.drainTasks()
            if (started && !ended) {
                if (!model.alive) {
                    model.alive = true
                    model.playRequested = false
                    player.fire { playing() }
                    fake.drainTasks()
                }
                asserted++
                val expected: Long = seekTarget ?: 0L
                assertEquals(expected, model.playhead, "seed $seed (restartOnEnd=$restartOnEnd): a seek was lost or sent into a dead input; steps $trace; calls ${player.calls}")
            }
        } finally {
            engine.dispose()
            fake.shutdown()
        }
    }
}
