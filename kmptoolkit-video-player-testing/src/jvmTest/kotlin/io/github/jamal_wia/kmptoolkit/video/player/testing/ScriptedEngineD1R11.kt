package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource

/** An engine that does what the test scripts and keeps a log of every call, so "after dispose" is checkable. */
internal class ScriptedEngineD1R11 : VideoPlaybackEngine {
    var sink: VideoPlaybackEngineListener? = null
    var keepListenerOnDetach: Boolean = false
    var duration: Long = 10_000L
    var position: Long = 0L
    var buffered: Long = 0L
    var loadAction: suspend () -> Unit = {}
    var onStart: () -> Unit = {}
    var onPause: () -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onSetSpeed: () -> Unit = {}
    var onRelease: () -> Unit = {}
    var onDispose: () -> Unit = {}
    var positionReading: () -> Long = { position }
    val log: MutableList<String> = mutableListOf()
    val seekTargets: MutableList<Long> = mutableListOf()
    val speeds: MutableList<Float> = mutableListOf()
    val volumes: MutableList<Float> = mutableListOf()
    var disposed: Boolean = false
    var playing: Boolean = false
    var lastLooping: Boolean = false

    private fun note(call: String) {
        if (disposed) log += "AFTER_DISPOSE:$call" else log += call
    }

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        note("setListener")
        if (listener != null || !keepListenerOnDetach) this.sink = listener
    }

    override suspend fun load(source: VideoSource) {
        note("load")
        loadAction()
    }

    override fun start() {
        note("start")
        playing = true
        onStart()
    }

    override fun pause() {
        note("pause")
        playing = false
        onPause()
    }

    override fun seekTo(positionMs: Long) {
        note("seek")
        seekTargets += positionMs
        position = positionMs
        onSeek(positionMs)
    }

    override fun setSpeed(speed: Float) {
        note("speed")
        speeds += speed
        onSetSpeed()
    }

    override fun setVolume(volume: Float) {
        note("volume")
        volumes += volume
    }

    override fun setLooping(looping: Boolean) {
        note("looping")
        lastLooping = looping
    }

    override fun durationMs(): Long = duration

    override fun positionMs(): Long = positionReading()

    override fun bufferedPositionMs(): Long = buffered

    override fun release() {
        note("release")
        playing = false
        onRelease()
    }

    override fun dispose() {
        note("dispose")
        disposed = true
        onDispose()
    }

    fun callsAfterDispose(): List<String> = log.filter { it.startsWith("AFTER_DISPOSE:") }
}
