package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * D2 round 14, Q2: every callback the engine registers on the native player, delivered by hand at an instant after each
 * transport call (the callbacks JavaFX queued before the call and runs after it), in a playing context. Virtual time.
 *
 * Invariants of a cell (the contract: `05-platform-notes.md` "Playback speed", the core guide "The release contract"):
 * no exception; no fragile window violated (the end-of-test hazard check of `fxModel`, W-IDLE included); no native call
 * after `dispose()`; the published position does not go backwards over the delivery (a seek, an end or a wrap excepted);
 * the engine did not report Completed while the decoder still runs; and 1.5 s later the decoder runs at the speed last
 * given when nothing ended, failed or released it.
 *
 * The cells that are red are collected in one message, so a single run shows the whole table.
 */
class CallbackTransportSettlePhaseMatrixR14ModelTest {

    private enum class Call { PAUSE, SEEK, START_AFTER_PAUSE, RELEASE, SPEED_TWO, SPEED_ONE, LOOP_FLIP, STOP_LIKE }

    private enum class Cb {
        END, STATUS_PLAYING, STATUS_PAUSED, STATUS_STALLED, STATUS_READY, TIME, BUFFER, DURATION, SIZE,
        PLAYER_ERROR, MEDIA_ERROR, READY, PULSE
    }

    private class Recorder : VideoPlaybackEngineListener {
        var completed: Int = 0
        var failed: Int = 0
        override fun onCompleted() { completed++ }
        override fun onFailed(cause: Throwable) { failed++ }
        override fun onBufferingChanged(isBuffering: Boolean) {}
        override fun onVideoSizeChanged(size: VideoSize?) {}
    }

    private fun cell(loop: Boolean, speed: Float, call: Call, cb: Cb, offsetMs: Long): String? {
        val settlePhase = true
        var problem: String? = null
        try {
            fxModel {
                val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = ClipModel(durationMs = 60_000.0), script = StatusScript()), timeScript = TimeScript())
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                val recorder = Recorder()
                engine.setListener(recorder)
                load(engine, VideoSource.Asset(TestClip.ASSET))
                val composed: ComposedPlayer = backend.last
                val native: ScriptedNativePlayer = composed.player
                try {
                    engine.setLooping(loop)
                    engine.start()
                    advanceBy(1_000)
                    engine.pause()
                    advanceBy(1_000)
                    engine.start()
                    engine.setSpeed(speed)
                    advanceBy(120)
                    var lastSpeed: Float = speed
                    var released = false
                    when (call) {
                        Call.PAUSE -> engine.pause()
                        Call.SEEK -> engine.seekTo(20_000L)
                        Call.START_AFTER_PAUSE -> {
                            engine.pause()
                            advanceBy(5)
                            engine.start()
                        }
                        Call.RELEASE -> { engine.release(); released = true }
                        Call.SPEED_TWO -> { engine.setSpeed(2f); lastSpeed = 2f }
                        Call.SPEED_ONE -> { engine.setSpeed(1f); lastSpeed = 1f }
                        Call.LOOP_FLIP -> engine.setLooping(!loop)
                        Call.STOP_LIKE -> { engine.pause(); engine.seekTo(0L) }
                    }
                    advanceBy(offsetMs)
                    val positionBefore: Long = engine.positionMs()
                    val events: NativeEvents = checkNotNull(native.events)
                    onFx {
                        when (cb) {
                            Cb.END -> if (released || !composed.loop.endCycle()) native.deliverEndOfMedia(ignoreClearedHandler = true)
                            Cb.STATUS_PLAYING -> native.forceStatus(NativeStatus.PLAYING)
                            Cb.STATUS_PAUSED -> native.forceStatus(NativeStatus.PAUSED)
                            Cb.STATUS_STALLED -> native.forceStatus(NativeStatus.STALLED)
                            Cb.STATUS_READY -> native.forceStatus(NativeStatus.READY)
                            Cb.TIME -> events.onCurrentTime()
                            Cb.BUFFER -> events.onBufferProgress()
                            Cb.DURATION -> events.onDuration()
                            Cb.SIZE -> events.onSize()
                            Cb.PLAYER_ERROR -> events.onPlayerError()
                            Cb.MEDIA_ERROR -> events.onMediaError()
                            Cb.READY -> events.onReady()
                            Cb.PULSE -> events.onPulse(host.nanoTime())
                        }
                    }
                    val positionAfter: Long = engine.positionMs()
                    val ends: Boolean = cb == Cb.END || cb == Cb.PLAYER_ERROR || cb == Cb.MEDIA_ERROR
                    if (!ends && call != Call.SEEK && call != Call.STOP_LIKE && positionAfter < positionBefore) {
                        problem = "the published position went backwards over the callback: $positionBefore -> $positionAfter"
                    }
                    advanceBy(1_500)
                    val entries: List<NativeCallEntry> = native.log.entries
                    val disposeAt: Int = entries.indexOfFirst { it.call == NativeCall.DISPOSE }
                    if (released) {
                        if (disposeAt < 0) problem = "release() did not dispose the player"
                        else {
                            val late: List<NativeCallEntry> = entries.drop(disposeAt + 1).filter { it.call == NativeCall.PLAY || it.call == NativeCall.PAUSE || it.call == NativeCall.SEEK || it.call == NativeCall.SET_RATE }
                            if (late.isNotEmpty()) problem = "native calls after dispose(): " + late.joinToString { "${it.call}@${it.tMs}" }
                        }
                    }
                    if (recorder.completed > 0 && composed.hazards.decoderRunning) {
                        problem = problem ?: "Completed was reported while the decoder runs"
                    }
                    val expectRunning: Boolean = !released && !ends && call != Call.PAUSE && call != Call.STOP_LIKE
                    if (expectRunning && !composed.hazards.decoderRunning) {
                        problem = problem ?: "the decoder does not run although nothing paused, ended or released it"
                    }
                    if (expectRunning && composed.hazards.decoderRunning && composed.hazards.effectiveRate != lastSpeed.toDouble()) {
                        problem = problem ?: "the decoder runs at ${composed.hazards.effectiveRate} after 1.5 s, the speed last given is $lastSpeed"
                    }
                    if ((call == Call.PAUSE || call == Call.STOP_LIKE) && !ends && composed.hazards.decoderRunning) {
                        problem = problem ?: "the decoder runs after pause()"
                    }
                } finally {
                    engine.release()
                    advanceBy(50)
                }
            }
        } catch (t: Throwable) {
            problem = (problem?.plus("; ") ?: "") + t.javaClass.simpleName + ": " + (t.message ?: "").lines().take(3).joinToString(" | ").take(300)
        }
        return problem
    }

    /**
     * A late status is delivered in order: the status the native side reports for a command lands before the PLAYING of the
     * next play(), so a PAUSED, STALLED or READY that arrives after PLAYING has landed is not an event JavaFX can send
     * (the model keeps such a forced status for good, and the speed waits for a PLAYING that no one will report).
     */
    private fun offsetsOf(cb: Cb): List<Long> = when (cb) {
        Cb.STATUS_PAUSED, Cb.STATUS_STALLED, Cb.STATUS_READY -> emptyList() // PLAYING landed 100 ms before: such a status cannot arrive now
        else -> listOf(0L, 5L, 150L)
    }

    private fun runTable(loop: Boolean, speed: Float): String {
        val findings: MutableList<String> = mutableListOf()
        var cells = 0
        for (call: Call in Call.entries) for (cb: Cb in Cb.entries) for (offset: Long in offsetsOf(cb)) {
            cells++
            val p: String? = cell(loop, speed, call, cb, offset)
            if (p != null) findings += "loop=$loop speed=$speed call=$call cb=$cb at +$offset ms: $p"
        }
        return "cells=$cells red=${findings.size}\n" + findings.joinToString("\n")
    }

    @Test
    fun noCallbackAfterATransportCallBreaksAnInvariantWhilePlayingTwiceAsFastAndNotLooping() {
        val table: String = runTable(loop = false, speed = 2f)
        assertTrue(table.contains("red=0\n"), table)
    }

    @Test
    fun noCallbackAfterATransportCallBreaksAnInvariantWhilePlayingTwiceAsFastAndLooping() {
        val table: String = runTable(loop = true, speed = 2f)
        assertTrue(table.contains("red=0\n"), table)
    }

    @Test
    fun noCallbackAfterATransportCallBreaksAnInvariantWhilePlayingAtNormalSpeedAndLooping() {
        val table: String = runTable(loop = true, speed = 1f)
        assertTrue(table.contains("red=0\n"), table)
    }

    @Test
    fun noCallbackAfterATransportCallBreaksAnInvariantWhilePlayingAtNormalSpeedAndNotLooping() {
        val table: String = runTable(loop = false, speed = 1f)
        assertTrue(table.contains("red=0\n"), table)
    }
}
