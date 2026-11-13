package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxHost
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The parts of one scripted player, composed: the time part ([TimeModel], B3 B5 B7), the hazard part ([HazardModel] with
 * its [HazardMonitor], B4 B6 B11) and the loop part ([LoopModel], B8), wired to each other. The three were written
 * apart; this is where they are put together, and the calibration scenarios are what proves the composition.
 *
 * WIRING, and who calls whom:
 * - the time part owns the end of a cycle: when the decoder reaches the duration it calls [LoopModel.endCycle] (see
 *   `TimeModel.reachEnd`), so nothing else triggers a wrap;
 * - the loop part tells the hazard part at the instant of the wrap: `loop.onWrap { hazards.onLoopWrap(player) }`
 *   opens W-WRAP and sets the decoder back to 1.0, BEFORE `onEndOfMedia` reaches `FxPlayback`;
 * - the hazard part's `frozen` and `decoderRunning` are what the time part reads, found through the player's hazard slot.
 *
 * @property player the scripted player.
 * @property monitor the hazard monitor (and through it the hazard part) of [player].
 * @property time the time part of [player].
 * @property loop the loop part of [player].
 */
internal class ComposedPlayer(
    val player: ScriptedNativePlayer,
    val monitor: HazardMonitor,
    val time: TimeModel,
    val loop: LoopModel,
) {
    /** The hazard part of [player]. */
    val hazards: HazardModel get() = monitor.hazards
}

/**
 * A [NativeBackend] whose players are each built with the time, hazard and loop parts of [ComposedPlayer], composed.
 *
 * OPT-IN: [ScriptedBackend] is final and a backend handed to the engine unwrapped is NOT watched: a player of a bare
 * [ScriptedBackend] has no hazard part, no monitor and no end-of-test check. A model test opts in by passing the
 * result of [FxModelScope.composedBackend] (or, without the time and loop parts, [FxModelScope.hazardBackend]) as the
 * engine's backend; that is the only place the end-of-test hazard check gets wired.
 */
internal class ComposedBackend(
    private val inner: ScriptedBackend,
    private val compose: (ScriptedNativePlayer) -> ComposedPlayer,
) : NativeBackend {

    private val built: MutableList<ComposedPlayer> = CopyOnWriteArrayList()

    override val host: FxHost get() = inner.host

    /** The composed players made so far, in creation order. */
    val players: List<ComposedPlayer> get() = built.toList()

    /** The newest composed player; fails when none was created yet. */
    val last: ComposedPlayer get() = checkNotNull(built.lastOrNull()) { "no player was created yet (${inner.host})" }

    override fun probe(uri: String): NativeSource = inner.probe(uri)

    override fun create(source: NativeSource): NativePlayer {
        val player: ScriptedNativePlayer = inner.create(source) as ScriptedNativePlayer
        built += compose(player)
        return player
    }
}

/**
 * Wraps [backend] so every player it creates gets the time, hazard and loop parts, composed, and its hazard monitor is
 * wired to the end of the test (a violated fragile window fails the test, see [FxModelScope.watchHazards]).
 *
 * @param timeScript the script of the time part; every player gets its own [TimeScript.copy].
 * @param hazardConfig the windows and the Q-M1 reading of the hazard part.
 * @param mode what the monitor does at a violation.
 * @param loopPublishHoldMs how long the published time of a new loop cycle holds, see [LoopModel].
 * @param watch whether the monitor is wired to the end of the test (see [FxModelScope.watchHazards]). False builds
 *   the monitor with `withHazards`, unwired: for a test that provokes or characterises a violation on purpose.
 */
internal fun FxModelScope.composedBackend(
    backend: ScriptedBackend,
    timeScript: TimeScript = TimeScript(),
    hazardConfig: HazardConfig = HazardConfig(),
    mode: MonitorMode = MonitorMode.FREEZE,
    loopPublishHoldMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS,
    watch: Boolean = true,
): ComposedBackend = ComposedBackend(backend) { player: ScriptedNativePlayer ->
    val monitor: HazardMonitor =
        if (watch) watchHazards(player, hazardConfig, mode) else player.withHazards(hazardConfig, mode)
    val loop = LoopModel(player, loopPublishHoldMs)
    player.loop = loop
    loop.onWrap { player.hazardModel?.onLoopWrap(player) }
    val time = TimeModel(player, timeScript.copy(), loop)
    ComposedPlayer(player, monitor, time, loop)
}
