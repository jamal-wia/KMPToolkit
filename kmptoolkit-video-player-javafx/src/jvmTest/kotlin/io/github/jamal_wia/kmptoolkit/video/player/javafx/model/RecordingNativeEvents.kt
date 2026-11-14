package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * A [NativeEvents] that writes down what it was told, with the virtual time and whether the call came on the
 * model's FX thread, for the self-tests of the scripted player and the parts that follow it.
 *
 * Names are `ready`, `playerError`, `mediaError`, `cycleDecision`, `endOfMedia`, `status:<STATUS>`, `currentTime`,
 * `bufferProgress`, `duration`, `size` and `pulse`.
 */
internal class RecordingNativeEvents(private val host: VirtualFxHost) : NativeEvents {

    /** One delivered event: the name, the virtual milliseconds since the host started, and the thread flag. */
    data class Event(val name: String, val tMs: Double, val onFxThread: Boolean)

    private val recorded: MutableList<Event> = mutableListOf()

    /** The events so far, oldest first. */
    val events: List<Event> get() = recorded.toList()

    /** The names of the events so far, oldest first. */
    val names: List<String> get() = recorded.map { it.name }

    /** Forgets what was recorded. */
    fun clear() = recorded.clear()

    private fun add(name: String) {
        recorded += Event(name, host.elapsedNanos / 1_000_000.0, host.isFxThread)
    }

    override fun onReady() = add("ready")

    override fun onPlayerError() = add("playerError")

    override fun onMediaError() = add("mediaError")

    override fun onCycleDecision() = add("cycleDecision")

    override fun onEndOfMedia() = add("endOfMedia")

    override fun onStatus(status: NativeStatus) = add("status:$status")

    override fun onCurrentTime() = add("currentTime")

    override fun onBufferProgress() = add("bufferProgress")

    override fun onDuration() = add("duration")

    override fun onSize() = add("size")

    override fun onPulse(nowNanos: Long) = add("pulse")
}
