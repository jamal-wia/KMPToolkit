package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * A player built the way `FxPlayback` builds one (on the FX thread, handlers installed at once), for the self-tests of
 * the loop, stall, dispose and picture parts.
 */
internal class PartTestRig(
    val scope: FxModelScope,
    val player: ScriptedNativePlayer,
    val events: RecordingNativeEvents,
) {
    fun advance(ms: Long) = scope.advanceBy(ms)

    /** Virtual milliseconds since the host started. */
    fun now(): Double = scope.host.elapsedNanos / 1_000_000.0

    /** `play()` on the FX thread, then the status lag, so the player reads PLAYING. */
    fun playToPlaying() {
        scope.onFx { player.play() }
        advance(StatusScript.DEFAULT_STATUS_LATENCY_MS.toLong())
        check(player.status == NativeStatus.PLAYING) { "expected PLAYING, was ${player.status}" }
    }
}

/**
 * Builds a [PartTestRig]: [install] puts the part under test in its slot before anything happens, then the clock runs
 * to the ready instant when [ready] is true, and the event record is cleared.
 */
internal fun FxModelScope.partRig(
    script: StatusScript = StatusScript(),
    clip: ClipModel = ClipModel(),
    ready: Boolean = true,
    install: (ScriptedNativePlayer) -> Unit = {},
): PartTestRig {
    val backend = ScriptedBackend(host, clip, script)
    val events = RecordingNativeEvents(host)
    val player: ScriptedNativePlayer = onFx {
        val created: ScriptedNativePlayer = backend.create(backend.probe("clip.mp4")) as ScriptedNativePlayer
        created.setEvents(events)
        install(created)
        created
    }
    val rig = PartTestRig(this, player, events)
    if (ready) rig.advance(script.readyLatencyMs.toLong())
    events.clear()
    return rig
}
