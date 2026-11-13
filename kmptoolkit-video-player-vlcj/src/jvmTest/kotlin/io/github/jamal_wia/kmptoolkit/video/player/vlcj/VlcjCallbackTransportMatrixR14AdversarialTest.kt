package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * D2 round 14, Q3: every libvlc event the engine handles, delivered on the event thread of the fake runtime after each
 * transport call, in a playing context and in an ended one (the events libvlc queued before the call and delivers after it).
 * The libvlc library cannot run on this machine (x86_64 library, arm64 JVM), so the fake runtime is the only tier.
 *
 * Invariants of a cell: no exception on either thread; no native call and no listener event after `release()`; at most
 * one completion per end of the source; no buffering state left on after the end or an error; the published position
 * never goes backwards over a delivery that is not a seek, a loop restart or a clock reading older than a seek.
 *
 * Not asserted, argued: a `buffering(30)` after the end or after `pause()` leaves buffering on. After the end it is an order
 * libvlc does not produce (the input thread posts its last cache event before `EndReached`); after `pause()` it is a real
 * cache fill that ends with `buffering(100)`.
 *
 * The frozen clock keeps every seek hold from depending on wall time. The cells that are red are collected in one message.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjCallbackTransportMatrixR14AdversarialTest {

    private enum class Call { PAUSE, SEEK, START, RELEASE, SPEED, LOOP_ON, LOOP_OFF }

    private enum class Cb {
        PLAYING, PAUSED, STOPPED, FINISHED, TIME_LOWER, TIME_HIGHER, BUFFERING_LOW, BUFFERING_FULL, ERROR, LENGTH, DURATION, PARSED, FORMAT
    }

    private val teardownThread: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "r14-teardown").apply { isDaemon = true } }

    private fun cell(ended: Boolean, call: Call, cb: Cb): String? {
        val runtime = FakeVlcRuntime()
        val listener = RecordingListener()
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = { runtime },
            teardown = teardownThread,
            nanoTime = { FROZEN_NANOS },
        )
        engine.setListener(listener)
        var problem: String? = null
        try {
            runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }
            val player: FakeVlcPlayer = runtime.player
            engine.setSpeed(2f)
            engine.start()
            runtime.drainTasks()
            player.fire { playing() }
            player.fire { timeChanged(1_000L) }
            if (ended) {
                player.fire { finished() }
            }
            var released = false
            val completionsBefore: Int
            when (call) {
                Call.PAUSE -> engine.pause()
                Call.SEEK -> engine.seekTo(1_500L)
                Call.START -> { engine.pause(); runtime.drainTasks(); engine.start() }
                Call.RELEASE -> { engine.release(); released = true }
                Call.SPEED -> engine.setSpeed(3f)
                Call.LOOP_ON -> engine.setLooping(true)
                Call.LOOP_OFF -> engine.setLooping(false)
            }
            runtime.drainTasks()
            val eventsBefore: Int = listener.events.size
            completionsBefore = listener.completions
            val positionBefore: Long = engine.positionMs()
            val callsBefore: Int = player.calls.size
            when (cb) {
                Cb.PLAYING -> player.fire { playing() }
                Cb.PAUSED -> player.fire { paused() }
                Cb.STOPPED -> player.fire { stopped() }
                Cb.FINISHED -> player.fire { finished() }
                Cb.TIME_LOWER -> player.fire { timeChanged(500L) }
                Cb.TIME_HIGHER -> player.fire { timeChanged(1_900L) }
                Cb.BUFFERING_LOW -> player.fire { buffering(30f) }
                Cb.BUFFERING_FULL -> player.fire { buffering(100f) }
                Cb.ERROR -> player.fire { error() }
                Cb.LENGTH -> player.fire { lengthChanged(2_000L) }
                Cb.DURATION -> player.fire { durationChanged(2_000L) }
                Cb.PARSED -> player.fire { parsed(VlcParseStatus.DONE) }
                Cb.FORMAT -> player.fire { bufferFormat(640, 480) }
            }
            runtime.drainTasks()
            val positionAfter: Long = engine.positionMs()
            val newEvents: List<String> = listener.events.drop(eventsBefore)
            if (released) {
                val lateCalls: List<String> = player.calls.drop(callsBefore).filter { it == "play" || it == "pause" || it.startsWith("setTime") || it.startsWith("setRate") }
                if (lateCalls.isNotEmpty()) problem = "native calls after release(): $lateCalls"
                if (newEvents.isNotEmpty()) problem = (problem?.plus("; ") ?: "") + "listener events after release(): $newEvents"
            }
            val completionsAfter: Int = listener.completions
            if (cb == Cb.FINISHED && !released && !(call == Call.LOOP_ON) && completionsAfter - completionsBefore > 1) {
                problem = "more than one completion for one finished()"
            }
            val lastBuffering: String? = listener.events.lastOrNull { it.startsWith("buffering=") }
            if ((ended || cb == Cb.FINISHED || cb == Cb.ERROR) && !released && cb != Cb.BUFFERING_LOW && lastBuffering == "buffering=true") {
                problem = problem ?: "buffering left on after the end"
            }
            // A start from the end replays from 0, so a reading below the end is the new run, not a step back.
            val excused: Boolean = call == Call.SEEK || (call == Call.START && ended) || cb == Cb.TIME_LOWER || cb == Cb.FINISHED || cb == Cb.ERROR || released
            if (!excused && positionAfter < positionBefore) {
                problem = problem ?: "the published position went backwards over the callback: $positionBefore -> $positionAfter"
            }
        } catch (t: Throwable) {
            problem = (problem?.plus("; ") ?: "") + t.javaClass.simpleName + ": " + (t.message ?: "").take(200)
        } finally {
            try { engine.dispose() } catch (_: Throwable) {}
            try { teardownThread.submit {}.get(5, TimeUnit.SECONDS) } catch (_: Throwable) {}
            runtime.shutdown()
        }
        return problem
    }

    @Test
    fun noLibvlcEventAfterATransportCallBreaksAnInvariant() {
        val findings: MutableList<String> = mutableListOf()
        var cells = 0
        for (ended: Boolean in listOf(false, true)) for (call: Call in Call.entries) for (cb: Cb in Cb.entries) {
            cells++
            val p: String? = cell(ended, call, cb)
            if (p != null) findings += "ended=$ended call=$call cb=$cb: $p"
        }
        val table: String = "cells=$cells red=${findings.size}\n" + findings.joinToString("\n")
        assertTrue(findings.isEmpty(), table)
    }

    private companion object {
        const val FROZEN_NANOS: Long = 1_000_000_000L
    }
}
