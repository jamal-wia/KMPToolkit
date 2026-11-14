package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxHost
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The parts of one scripted player, composed by [composePlayer]: the time part ([TimeModel], B3 B5 B7), the hazard part
 * ([HazardModel] with its [HazardMonitor], B4 B6 B11), the loop, stall and dispose parts ([LoopModel] B8, [StallModel]
 * B10, [DisposeModel] B12, in one [ModelPartGroup] in the player's `loop` slot) and the picture part
 * ([PictureModel], B13), wired to each other. The parts were written apart; this is where they are put together, and
 * the calibration scenarios are what proves the composition.
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
 * @property stall the stall part of [player]; it does nothing until a test calls `stallAt`.
 * @property dispose the dispose part of [player]; it says nothing after DISPOSED unless [composePlayer] was given a late
 *   status or a late end of media.
 * @property picture the picture part of [player].
 */
internal class ComposedPlayer(
    val player: ScriptedNativePlayer,
    val monitor: HazardMonitor,
    val time: TimeModel,
    val loop: LoopModel,
    val stall: StallModel,
    val dispose: DisposeModel,
    val picture: PictureModel,
) {
    /** The hazard part of [player]. */
    val hazards: HazardModel get() = monitor.hazards
}

/**
 * A [NativeBackend] whose players are each built with the time, hazard and loop parts of [ComposedPlayer], composed.
 *
 * WATCHED BY DEFAULT: a player of a bare [ScriptedBackend] has no hazard part, no monitor and no end-of-test check, and
 * the end-of-test guard of `fxModel` fails a test that leaves one unwatched unless it calls
 * [FxModelScope.allowUnwatched]. A model test passes the result of [FxModelScope.composeAll] (or, without the time,
 * loop and other parts, [FxModelScope.hazardBackend]) as the engine's backend; that is where the end-of-test hazard
 * check gets wired.
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
 * The one builder of the model tier: puts every part of a scripted player in place, composed (see [ComposedPlayer]).
 * Family tests build their engines through it, so none of them loses a link silently (the B8 wrap link, the stall,
 * dispose and picture parts).
 *
 * The hazard part is watched by default (see [FxModelScope.watchHazards]): a violated fragile window fails the test,
 * and so does a violation recorded by the dispose part ([DisposeModel.violations]), checked at the end of the test.
 *
 * @param player a player of the scope's host, with every slot empty.
 * @param timeScript the script of the time part; the player gets its own [TimeScript.copy].
 * @param hazardConfig the windows and the Q-M1 reading of the hazard part.
 * @param mode what the monitor does at a violation.
 * @param loopPublishHoldMs how long the published time of a new loop cycle holds, see [LoopModel].
 * @param watch whether the monitor, and the dispose violations, are wired to the end of the test. False builds the
 *   monitor with `withHazards`, unwired: for a test that provokes or characterises a violation on purpose; that test
 *   must also call [FxModelScope.allowUnwatched].
 * @param bufferAheadMs how far ahead of the playhead the buffer runs after a stall, see [StallModel].
 * @param lateStatus the status that lands after a dispose, or null for none, see [DisposeModel].
 * @param lateEndOfMedia whether an end of media runs after a dispose, see [DisposeModel].
 * @param lateEndIgnoresClearedHandler whether that late end is delivered even after `clearEndOfMedia`.
 * @param pulsePeriodNanos the pulse period of the picture part.
 */
internal fun FxModelScope.composePlayer(
    player: ScriptedNativePlayer,
    timeScript: TimeScript = TimeScript(),
    hazardConfig: HazardConfig = HazardConfig(),
    mode: MonitorMode = MonitorMode.FREEZE,
    loopPublishHoldMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS,
    watch: Boolean = true,
    bufferAheadMs: Double = StallModel.DEFAULT_BUFFER_AHEAD_MS,
    lateStatus: NativeStatus? = null,
    lateEndOfMedia: Boolean = false,
    lateEndIgnoresClearedHandler: Boolean = false,
    pulsePeriodNanos: Double = PictureModel.DEFAULT_PULSE_PERIOD_NANOS,
): ComposedPlayer {
    val monitor: HazardMonitor =
        if (watch) watchHazards(player, hazardConfig, mode) else player.withHazards(hazardConfig, mode)
    val loop = LoopModel(player, loopPublishHoldMs)
    val stall = StallModel(player, bufferAheadMs)
    val dispose = DisposeModel(
        player,
        lateStatus = lateStatus,
        lateEndOfMedia = lateEndOfMedia,
        lateEndIgnoresClearedHandler = lateEndIgnoresClearedHandler,
    )
    player.loop = ModelPartGroup(loop, stall, dispose)
    val picture = PictureModel(player, pulsePeriodNanos)
    player.picture = picture
    loop.onWrap { player.hazardModel?.onLoopWrap(player) }
    val time = TimeModel(player, timeScript.copy(), loop)
    if (watch) {
        afterModel("no dispose violation (player ${host.players.indexOf(player) + 1})") {
            if (dispose.violations.isNotEmpty()) {
                throw AssertionError("the dispose part recorded ${dispose.violations.size} violation(s): ${dispose.violations} ($host)")
            }
        }
    }
    return ComposedPlayer(player, monitor, time, loop, stall, dispose, picture)
}

/**
 * Wraps [backend] so every player it creates is built by [composePlayer]; pass the result as the engine's backend. The
 * parameters are those of [composePlayer].
 */
internal fun FxModelScope.composeAll(
    backend: ScriptedBackend,
    timeScript: TimeScript = TimeScript(),
    hazardConfig: HazardConfig = HazardConfig(),
    mode: MonitorMode = MonitorMode.FREEZE,
    loopPublishHoldMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS,
    watch: Boolean = true,
    bufferAheadMs: Double = StallModel.DEFAULT_BUFFER_AHEAD_MS,
    lateStatus: NativeStatus? = null,
    lateEndOfMedia: Boolean = false,
    lateEndIgnoresClearedHandler: Boolean = false,
    pulsePeriodNanos: Double = PictureModel.DEFAULT_PULSE_PERIOD_NANOS,
): ComposedBackend = ComposedBackend(backend) { player: ScriptedNativePlayer ->
    composePlayer(
        player, timeScript, hazardConfig, mode, loopPublishHoldMs, watch, bufferAheadMs, lateStatus, lateEndOfMedia,
        lateEndIgnoresClearedHandler, pulsePeriodNanos,
    )
}

/**
 * The calibration name of [composeAll] with the time, hazard, loop, stall, dispose and picture parts all composed: see
 * [composeAll] for the parameters. Kept so existing callers read as before.
 */
internal fun FxModelScope.composedBackend(
    backend: ScriptedBackend,
    timeScript: TimeScript = TimeScript(),
    hazardConfig: HazardConfig = HazardConfig(),
    mode: MonitorMode = MonitorMode.FREEZE,
    loopPublishHoldMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS,
    watch: Boolean = true,
): ComposedBackend = composeAll(backend, timeScript, hazardConfig, mode, loopPublishHoldMs, watch)
