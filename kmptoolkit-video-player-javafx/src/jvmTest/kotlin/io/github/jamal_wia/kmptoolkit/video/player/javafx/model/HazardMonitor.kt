package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource

/** What a [HazardMonitor] does at the moment a fragile window is violated (design 4.3). */
internal enum class MonitorMode {
    /**
     * The default, faithful to the native player: the decoder freezes (the [HazardModel] always does that) and the
     * test goes on; it then fails on its own "playhead moved" assertion and on the end-of-test check.
     */
    FREEZE,

    /** The call that violated the window throws an [AssertionError] at once, with the last log entries; for the fuzzers. */
    FAIL_FAST,
}

/**
 * The contract monitor of a scripted player (design 4.3): it watches the [HazardModel] of one player and turns a
 * violated fragile window into a test failure. In [MonitorMode.FREEZE] it only collects, and [assertNoViolation]
 * (wired to the end of every model test by `FxModelScope.watchHazards`) fails; in [MonitorMode.FAIL_FAST] the
 * offending call itself throws.
 *
 * Every message names the window, the call, the distances in virtual time, and the last [tailCount] entries of the
 * native call log. Strict-reading hits (Q-M1, [HazardModel.strictHits]) never fail anything; [strictReport] lists them.
 *
 * @param player the watched player; its log supplies the tail of every message.
 * @param hazards the player's hazard part.
 * @param mode when to fail.
 * @param tailCount how many log entries a message shows; 20 by design.
 */
internal class HazardMonitor(
    val player: ScriptedNativePlayer,
    val hazards: HazardModel,
    val mode: MonitorMode = MonitorMode.FREEZE,
    val tailCount: Int = NativeCallLog.DEFAULT_TAIL,
) {
    init {
        require(tailCount > 0) { "tailCount must be positive, was $tailCount" }
        hazards.addObserver { violation: HazardViolation ->
            if (mode == MonitorMode.FAIL_FAST) throw AssertionError(describe(violation))
        }
    }

    /** The violations so far, oldest first. */
    val violations: List<HazardViolation> get() = hazards.violations

    /** The message for one violation: the window, the distances, then the log tail. */
    fun describe(violation: HazardViolation): String =
        "fragile window violated, ${violation.describe()}; the decoder is frozen (mode $mode, ${player.host})\n" +
            player.log.tail(tailCount)

    /** Fails when any window was violated, naming all of them and the log tail. */
    fun assertNoViolation() {
        val all: List<HazardViolation> = violations
        if (all.isEmpty()) return
        throw AssertionError(
            "no fragile window may be violated, but ${all.size} were:\n" +
                all.joinToString("\n") { "  " + it.describe() } +
                "\n(mode $mode, ${player.host})\n" + player.log.tail(tailCount),
        )
    }

    /** The strict-reading list of Q-M1 as text, one line per hit, or a line saying there are none. */
    fun strictReport(): String {
        val hits: List<HazardViolation> = hazards.strictHits
        if (hits.isEmpty()) return "strict reading of Q-M1: no hits"
        return "strict reading of Q-M1: ${hits.size} hit(s)\n" + hits.joinToString("\n") { "  " + it.describe() }
    }
}

/**
 * Installs a [HazardModel] in the hazard slot of this player and returns the [HazardMonitor] watching it. The
 * player's slot must be empty; use `FxModelScope.watchHazards` in a model test, which also wires the end-of-test check.
 */
internal fun ScriptedNativePlayer.withHazards(
    config: HazardConfig = HazardConfig(),
    mode: MonitorMode = MonitorMode.FREEZE,
    tailCount: Int = NativeCallLog.DEFAULT_TAIL,
): HazardMonitor {
    check(hazard === ModelPart.None) { "the hazard slot of this player is already taken by $hazard" }
    val model = HazardModel(host, config)
    hazard = model
    return HazardMonitor(this, model, mode, tailCount)
}

/**
 * A [NativeBackend] that gives every [ScriptedNativePlayer] its delegate builds a hazard part and monitor through
 * [install] right after creation, so an engine built over it runs every player under the monitor. Create it with
 * `FxModelScope.hazardBackend`.
 */
internal class HazardBackend(
    private val delegate: ScriptedBackend,
    private val install: (ScriptedNativePlayer) -> HazardMonitor,
) : NativeBackend {

    override val host: VirtualFxHost get() = delegate.host

    override fun probe(uri: String): NativeSource = delegate.probe(uri)

    override fun create(source: NativeSource): NativePlayer {
        val player: NativePlayer = delegate.create(source)
        install(player as ScriptedNativePlayer)
        return player
    }
}
