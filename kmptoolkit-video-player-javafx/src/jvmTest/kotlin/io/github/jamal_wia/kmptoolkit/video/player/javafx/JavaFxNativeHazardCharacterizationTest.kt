package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume

/**
 * Opt-in hazard probes of the macOS (AVFoundation) native rate freeze, on `MediaPlayer` directly (task H0 of the JavaFX
 * port, plans/javafx-port-ruling.md, ruling 3.1). The gate never runs it: it is skipped unless the build forwards
 * `-Pkmptoolkit.test.hazards=true` as the `kmptoolkit.test.hazards` system property. It answers Q-M1: may the
 * `giveNudge` path and the `nearStart` path of `FxPlayback` write a rate right after a native seek on a player that
 * is not running?
 *
 * Five probes of 40 rounds each, one real video `MediaPlayer` per probe (never disposed per round: a video player
 * disposed while its CoreVideo display link starts can kill the JVM, see docs/kmptoolkit-video-player-javafx/05-platform-notes.md,
 * "Known issue"), rounds interleaved SEEK-RUN, NUDGE, NEARSTART, CONTROL so that drift of the machine hits all four alike:
 *  - SEEK-RUN: PLAYING for 500 ms, `seek(0)`, then a rate change 0 to 50 ms later (the D2-PF1 window);
 *  - NUDGE: PAUSED, `seek(0)`, `play()`, then `rate = 1.0; rate = r` two FX tasks later (`giveNudge`);
 *  - NEARSTART: PAUSED at 0, `seek(currentTime)`, `play()`, `rate = 1.0; rate = r` in the same FX task (`nearStart`);
 *  - CONTROL: SEEK-RUN with 400 ms between the seek and the rate change;
 *  - FIRSTPLAY (added at the lead's order after the Opus J5-J8 review): a fresh clip, `play()`, then in the next FX task
 *    `seek(0)`, `play()`, `rate = 1.0; rate = r` in that same task. It needs a fresh player every round, so it is the one
 *    probe that creates a player per round (40 players, none disposed, each left paused).
 *
 * "Frozen" means: after the stimulus and a settle pause of [SETTLE_MS], status PLAYING and `currentTime` unchanged for
 * [FROZEN_BOUND_MS]. The only assertion is harness sanity (every probe reached PLAYING and moved at least once); the
 * result is the printed table `HAZARD <probe> frozen=k/40`. The measurement is event driven (a `currentTime` listener
 * armed on the FX thread); the only real-time pauses are the stimulus gaps, which are the independent variable.
 *
 * Task J14b adds the remaining probes of the same shape, each 40 rounds interleaved with a CONTROL row whose gap lies
 * well outside the window (so the probe's power is shown), run by two further tests of this class:
 *  - W-PLAY: a plain `play()` (rate property 1.0, no seek since the last play, except a rewind when the playhead is past
 *    [RESEEK_FROM_MS] so that no wrap falls in the window), then a rate write 0 to 150 ms later (the model's window: 100 ms);
 *    CONTROL gap 500 ms. This is not the shape the window was found with (round 5: `play()` and the away-and-back in the
 *    same FX task, with the property at the speed); a 0/40 here says nothing about that shape;
 *  - W-IDLE: a rate write on the PAUSED player, then `play()` 0 to 300 ms later (the idle gap, the model's window: 200 ms);
 *    CONTROL gap 600 ms;
 *  - W-WRAP: PLAYING at 1.0 and seeked to [WRAP_LEAD_MS] before the end of the looping clip; a rate write 0 to 150 ms after
 *    the playhead wraps (the model's window: 100 ms); CONTROL gap 400 ms;
 *  - B4: the rate property holds `r` (written while paused, [QUIET_MS] before the play), then a plain `play()`; the speed
 *    of the playhead over [SLOPE_WINDOW_MS] decides whether the decoder runs at 1.0 (the model's B4) or at `r`; its
 *    CONTROL writes `r` on a player that has played for [PLAYED_MS] (away from a loop wrap the decoder then runs at `r`,
 *    which shows that the slope separates the two; a round whose measuring span holds a wrap is reported as WRAPPED and not
 *    counted, because at a wrap the decoder restarts at 1.0, B8, and the published time holds about 300 ms). A wrap is a
 *    decrease of `currentTime` or an `onEndOfMedia`, watched from the same FX task as the `play()` (B4) or the rate write
 *    (B4-CONTROL) until the slope window ends; the B4-CONTROL setup rewinds to 0 every round, B4 keeps its plain play.
 *    Here `frozen` is a slope of 0.1 or less in a round without a wrap; it is not the H0 freeze measure, and `HAZARD-B4`
 *    lists the other outcomes with `wrapped=k`.
 * Added after the first results showed no freeze in W-PLAY and W-IDLE at all (the original finding, round 5 of D2, was
 * a rate written together with `play()` and the idle gap found at a speed of 8): W-PLAY-NEAR (the W-PLAY stimulus with the
 * gap spread over 0 to 20 ms only, CONTROL gap 500 ms like W-PLAY; like W-PLAY it is not the shape the window was found
 * with, and a 0/40 here says nothing about that shape) and W-IDLE-8X (the W-IDLE stimulus at a speed of 8 with
 * the gap spread over 0 to 100 ms, CONTROL gap 600 ms at a speed of 8).
 * W-WRAP at speed (a rate write at a wrap while already running at a speed) is not probed: the A14c verdict (D-97) found
 * no defect, and the ruling made it optional.
 */
class JavaFxNativeHazardCharacterizationTest {

    private enum class Probe(val label: String) {
        SEEK_RUN("SEEK-RUN"),
        NUDGE("NUDGE"),
        NEARSTART("NEARSTART"),
        CONTROL("CONTROL"),
        FIRSTPLAY("FIRSTPLAY"),
        W_PLAY("W-PLAY"),
        W_PLAY_CONTROL("W-PLAY-CONTROL"),
        W_IDLE("W-IDLE"),
        W_IDLE_CONTROL("W-IDLE-CONTROL"),
        W_PLAY_NEAR("W-PLAY-NEAR"),
        W_IDLE_8X("W-IDLE-8X"),
        W_IDLE_8X_CONTROL("W-IDLE-8X-CONTROL"),
        W_WRAP("W-WRAP"),
        W_WRAP_CONTROL("W-WRAP-CONTROL"),
        B4("B4"),
        B4_CONTROL("B4-CONTROL"),
    }

    private enum class Outcome { MOVED, FROZEN, NOT_PLAYING, ONE_X, AT_RATE, OTHER_SLOPE, WRAPPED }

    /**
     * Watches one player for a loop wrap, a `currentTime` decrease or an `onEndOfMedia`, from [arm] until [disarm]. Both
     * run on the FX thread (inside `fx {}`), as do the callbacks; [wrapped] may be read from any thread.
     */
    private class WrapDetector(private val player: MediaPlayer) {
        private val sawWrap = AtomicBoolean(false)

        @Volatile
        var posAtArmMs: Double = -1.0
            private set
        private var last: Double = -1.0
        private val listener: ChangeListener<Duration> = object : ChangeListener<Duration> {
            override fun changed(observable: ObservableValue<out Duration>, old: Duration?, new: Duration?) {
                val now: Double = new?.toMillis() ?: return
                if (last >= 0.0 && now < last) sawWrap.set(true)
                last = now
            }
        }

        fun arm() {
            posAtArmMs = player.currentTime.toMillis()
            last = posAtArmMs
            player.currentTimeProperty().addListener(listener)
            player.onEndOfMedia = Runnable { sawWrap.set(true) }
        }

        fun disarm() {
            player.currentTimeProperty().removeListener(listener)
            player.onEndOfMedia = null
        }

        fun wrapped(): Boolean = sawWrap.get()
    }

    private class Subject(val probe: Probe) {
        lateinit var player: MediaPlayer

        fun hasPlayer(): Boolean = ::player.isInitialized
        var replaced: Int = 0
        var reachedPlaying: Boolean = false
        var lastSetupReseeked: Boolean = false
        val outcomes: MutableMap<Outcome, Int> = Outcome.values().associateWith { 0 }.toMutableMap()
        val frozenRounds: MutableList<String> = mutableListOf()
        val slopes: MutableList<String> = mutableListOf()
        val setupFailures: MutableList<String> = mutableListOf()
    }

    @Test
    fun `the native rate freeze windows are measured on real media and printed`() {
        runProbes(SEEK_PROBES)
    }

    @Test
    fun `the play window and the idle gap are measured on real media and printed`() {
        runProbes(listOf(Probe.W_PLAY, Probe.W_PLAY_CONTROL, Probe.W_IDLE, Probe.W_IDLE_CONTROL))
    }

    @Test
    fun `the narrow gaps of the play window and the idle gap at a speed of eight are measured on real media and printed`() {
        runProbes(listOf(Probe.W_PLAY_NEAR, Probe.W_PLAY_CONTROL, Probe.W_IDLE_8X, Probe.W_IDLE_8X_CONTROL))
    }

    @Test
    fun `the wrap window and the decoder rate after a plain play are measured on real media and printed`() {
        runProbes(listOf(Probe.W_WRAP, Probe.W_WRAP_CONTROL, Probe.B4, Probe.B4_CONTROL))
    }

    /** Runs [probes] interleaved for [ROUNDS] rounds and prints the table; skipped unless the opt-in property is true. */
    private fun runProbes(probes: List<Probe>) {
        Assume.assumeTrue(
            "opt-in probes: run with -Pkmptoolkit.test.hazards=true",
            System.getProperty(HAZARDS_PROPERTY) == "true",
        )
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val clipUri: String = TestClip.copyToTempFile().toURI().toString()

        val subjects: List<Subject> = probes.map { Subject(it) }
        subjects.filter { it.probe != Probe.FIRSTPLAY }.forEach { create(it, clipUri) }

        for (round: Int in 0 until ROUNDS) {
            for (subject: Subject in subjects) runRound(subject, round, clipUri)
        }

        // Not disposed: the process exit releases the players; a video player disposed right after its display
        // link ran is the documented crash window.
        for (subject: Subject in subjects) {
            val frozen: Int = subject.outcomes.getValue(Outcome.FROZEN)
            println("HAZARD ${subject.probe.label} frozen=$frozen/$ROUNDS")
            println(
                "HAZARD-DETAIL ${subject.probe.label} moved=${subject.outcomes.getValue(Outcome.MOVED)} " +
                    "notPlaying=${subject.outcomes.getValue(Outcome.NOT_PLAYING)} replacedPlayers=${subject.replaced} " +
                    "setupFailures=${subject.setupFailures.size}",
            )
            if (subject.probe in SLOPE_PROBES) {
                println(
                    "HAZARD-B4 ${subject.probe.label} decoderAt1x=${subject.outcomes.getValue(Outcome.ONE_X)} " +
                        "decoderAtRate=${subject.outcomes.getValue(Outcome.AT_RATE)} " +
                        "otherSlope=${subject.outcomes.getValue(Outcome.OTHER_SLOPE)} " +
                        "wrapped=${subject.outcomes.getValue(Outcome.WRAPPED)}",
                )
                subject.slopes.forEach { println("HAZARD-SLOPE ${subject.probe.label} $it") }
            }
            subject.frozenRounds.forEach { println("HAZARD-FROZEN ${subject.probe.label} $it") }
            subject.setupFailures.forEach { println("HAZARD-SETUP ${subject.probe.label} $it") }
        }
        for (subject: Subject in subjects) {
            assertTrue(subject.reachedPlaying, "${subject.probe.label}: the player never reached PLAYING with a moving playhead")
        }
    }

    private fun runRound(subject: Subject, round: Int, clipUri: String) {
        if (subject.probe in WINDOW_PROBES) {
            runWindowRound(subject, round, clipUri)
            return
        }
        val rate: Double = RATES[round % RATES.size]
        var ready: Boolean = setup(subject, clipUri)
        if (!ready) {
            // A player left frozen by an earlier round that pause, seek and play cannot revive: replace it once.
            subject.setupFailures += "round $round: setup failed on the player in use, replacing it"
            replace(subject, clipUri)
            ready = setup(subject, clipUri)
        }
        if (!ready) fail("${subject.probe.label} round $round: no PLAYING player after replacing it, the harness is broken")
        val player: MediaPlayer = subject.player

        when (subject.probe) {
            Probe.SEEK_RUN, Probe.CONTROL -> {
                fx { player.seek(Duration.ZERO) }
                val gapMs: Long = if (subject.probe == Probe.CONTROL) CONTROL_GAP_MS else (round * 7L) % (MAX_SEEK_RUN_GAP_MS + 1)
                stimulusPause(gapMs)
                fx { player.rate = rate }
            }
            Probe.NUDGE -> {
                val done = CountDownLatch(1)
                fx {
                    player.seek(Duration.ZERO)
                    player.play()
                    // Two FX tasks later, as FxPlayback.start() hands over the nudge.
                    Platform.runLater {
                        Platform.runLater {
                            player.rate = 1.0
                            player.rate = rate
                            done.countDown()
                        }
                    }
                }
                check(done.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)) { "the nudge tasks did not run" }
            }
            Probe.FIRSTPLAY -> {
                fx { player.play() }
                fx {
                    player.seek(Duration.ZERO)
                    player.play()
                    player.rate = 1.0
                    player.rate = rate
                }
            }
            Probe.NEARSTART -> fx {
                player.seek(player.currentTime)
                player.play()
                player.rate = 1.0
                player.rate = rate
            }
            else -> error("${subject.probe.label} is a window probe")
        }

        val outcome: Outcome = observe(player)
        subject.outcomes[outcome] = subject.outcomes.getValue(outcome) + 1
        // A moved playhead or a status of PLAYING after the stimulus proves the probe reached PLAYING.
        if (outcome != Outcome.NOT_PLAYING) subject.reachedPlaying = true
        if (outcome == Outcome.FROZEN) {
            subject.frozenRounds += "round=$round rate=$rate gapMs=" + when (subject.probe) {
                Probe.SEEK_RUN -> ((round * 7L) % (MAX_SEEK_RUN_GAP_MS + 1)).toString()
                Probe.CONTROL -> CONTROL_GAP_MS.toString()
                else -> "-"
            }
        }
        // Leave it paused for the next round's setup, which proves the player still plays.
        fx { player.pause() }
    }

    /**
     * Brings [subject] to the start state of its probe. Returns false when a PLAYING player does not move (a freeze
     * the previous round left behind).
     */
    private fun setup(subject: Subject, clipUri: String): Boolean {
        if (subject.probe == Probe.FIRSTPLAY) {
            // A fresh clip every round; the previous player stays paused and is never disposed (CoreVideo window).
            if (subject.hasPlayer()) fx { subject.player.pause() }
            create(subject, clipUri)
            return true
        }
        val player: MediaPlayer = subject.player
        fx { player.pause() }
        if (!awaitStatus(player, MediaPlayer.Status.PAUSED)) return false
        fx { player.seek(Duration.ZERO) }
        stimulusPause(SETTLE_MS)
        when (subject.probe) {
            Probe.SEEK_RUN, Probe.CONTROL -> {
                fx { player.play() }
                if (!awaitStatus(player, MediaPlayer.Status.PLAYING)) return false
                if (!awaitMoving(player)) return false
                subject.reachedPlaying = true
                stimulusPause(PLAYED_MS)
            }
            else -> Unit
        }
        return true
    }

    /** One round of a window probe (W-PLAY, W-IDLE, W-WRAP, B4 and their controls); see the class comment for each stimulus. */
    private fun runWindowRound(subject: Subject, round: Int, clipUri: String) {
        val probe: Probe = subject.probe
        val rate: Double = if (probe in EIGHT_X_PROBES) EIGHT_X else RATES[round % RATES.size]
        var ready: Boolean = setupWindow(subject, rate)
        if (!ready) {
            subject.setupFailures += "round $round: setup failed on the player in use, replacing it"
            replace(subject, clipUri)
            ready = setupWindow(subject, rate)
        }
        if (!ready) fail("${probe.label} round $round: no PAUSED player after replacing it, the harness is broken")
        val player: MediaPlayer = subject.player
        val gapMs: Long = windowGapMs(probe, round)
        var note: String = ""
        var outcome: Outcome? = null
        var detector: WrapDetector? = null

        when (probe) {
            Probe.W_PLAY, Probe.W_PLAY_CONTROL, Probe.W_PLAY_NEAR -> {
                note = if (subject.lastSetupReseeked) " reseek=true" else " reseek=false"
                fx { player.play() }
                stimulusPause(gapMs)
                fx { player.rate = rate }
            }
            Probe.W_IDLE, Probe.W_IDLE_CONTROL, Probe.W_IDLE_8X, Probe.W_IDLE_8X_CONTROL -> {
                fx { player.rate = rate }
                stimulusPause(gapMs)
                fx { player.play() }
            }
            Probe.W_WRAP, Probe.W_WRAP_CONTROL -> {
                val wrapped = CountDownLatch(1)
                var last: Double = -1.0
                val listener: ChangeListener<Duration> = object : ChangeListener<Duration> {
                    override fun changed(observable: ObservableValue<out Duration>, old: Duration?, new: Duration?) {
                        val now: Double = new?.toMillis() ?: return
                        if (last >= 0.0 && now < last) wrapped.countDown()
                        last = now
                    }
                }
                fx {
                    player.currentTimeProperty().addListener(listener)
                    player.play()
                }
                val sawWrap: Boolean = wrapped.await(WRAP_BOUND_MS, TimeUnit.MILLISECONDS)
                fx { player.currentTimeProperty().removeListener(listener) }
                if (sawWrap) {
                    stimulusPause(gapMs)
                    fx { player.rate = rate }
                } else {
                    subject.setupFailures += "round $round: no wrap seen within $WRAP_BOUND_MS ms, no stimulus given"
                    outcome = Outcome.NOT_PLAYING
                }
            }
            Probe.B4, Probe.B4_CONTROL -> {
                // The wrap detector is armed in the same FX task as the play (B4) or the rate write (B4-CONTROL).
                val armed = WrapDetector(player)
                detector = armed
                fx {
                    if (probe == Probe.B4) armed.arm()
                    player.play()
                }
                if (!awaitStatus(player, MediaPlayer.Status.PLAYING) || !awaitMoving(player)) {
                    outcome = if (fx { player.status } == MediaPlayer.Status.PLAYING) Outcome.FROZEN else Outcome.NOT_PLAYING
                } else if (probe == Probe.B4_CONTROL) {
                    stimulusPause(PLAYED_MS)
                    fx {
                        armed.arm()
                        player.rate = rate
                    }
                }
            }
            else -> error("${probe.label} is not a window probe")
        }

        if (outcome == null) {
            if (probe == Probe.B4 || probe == Probe.B4_CONTROL) {
                stimulusPause(SETTLE_MS)
                val slope: Double = measureSlope(player)
                val armed: WrapDetector = checkNotNull(detector) { "${probe.label} round $round: no wrap detector" }
                val sawWrap: Boolean = armed.wrapped()
                outcome = if (sawWrap) Outcome.WRAPPED else classifySlope(slope, rate)
                val posLabel: String = if (probe == Probe.B4) "posAtPlay" else "posAtWrite"
                subject.slopes += "round=$round rate=$rate $posLabel=${"%.0f".format(armed.posAtArmMs)} wrapped=$sawWrap " +
                    "slope=${"%.3f".format(slope)} outcome=$outcome"
            } else {
                outcome = observe(player)
            }
        }
        detector?.let { fx { it.disarm() } }
        subject.outcomes[outcome] = subject.outcomes.getValue(outcome) + 1
        if (outcome != Outcome.NOT_PLAYING) subject.reachedPlaying = true
        if (outcome == Outcome.FROZEN) {
            subject.frozenRounds += "round=$round rate=$rate gapMs=$gapMs$note"
        }
        fx { player.pause() }
    }

    /** The stimulus gap of a window probe in [round]: the probe's range spread over the rounds, or the control's fixed gap. */
    private fun windowGapMs(probe: Probe, round: Int): Long = when (probe) {
        Probe.W_PLAY, Probe.W_WRAP -> round * W_PLAY_RANGE_MS / (ROUNDS - 1)
        Probe.W_IDLE -> round * W_IDLE_RANGE_MS / (ROUNDS - 1)
        Probe.W_PLAY_NEAR -> round * W_NEAR_RANGE_MS / (ROUNDS - 1)
        Probe.W_IDLE_8X -> round * W_IDLE_8X_RANGE_MS / (ROUNDS - 1)
        Probe.W_IDLE_8X_CONTROL -> W_IDLE_CONTROL_GAP_MS
        Probe.W_PLAY_CONTROL -> W_PLAY_CONTROL_GAP_MS
        Probe.W_IDLE_CONTROL -> W_IDLE_CONTROL_GAP_MS
        Probe.W_WRAP_CONTROL -> W_WRAP_CONTROL_GAP_MS
        else -> 0L
    }

    /**
     * Brings a window probe to its start state: PAUSED, then the paused write of the property the probe needs, and a
     * quiet pause of [QUIET_MS] (outside the idle gap) where the stimulus is a play. Returns false when the player does
     * not pause.
     */
    private fun setupWindow(subject: Subject, rate: Double): Boolean {
        val player: MediaPlayer = subject.player
        subject.lastSetupReseeked = false
        fx { player.pause() }
        if (!awaitStatus(player, MediaPlayer.Status.PAUSED)) return false
        when (subject.probe) {
            Probe.W_PLAY, Probe.W_PLAY_CONTROL, Probe.W_PLAY_NEAR -> {
                fx { player.rate = 1.0 }
                if (fx { player.currentTime.toMillis() } > RESEEK_FROM_MS) {
                    fx { player.seek(Duration.ZERO) }
                    subject.lastSetupReseeked = true
                }
                stimulusPause(QUIET_MS)
            }
            Probe.W_IDLE, Probe.W_IDLE_CONTROL, Probe.W_IDLE_8X, Probe.W_IDLE_8X_CONTROL -> {
                fx { player.rate = 1.0 }
                stimulusPause(QUIET_MS)
            }
            Probe.W_WRAP, Probe.W_WRAP_CONTROL -> {
                val durationMs: Double = fx { player.totalDuration.toMillis() }
                fx {
                    player.rate = 1.0
                    player.seek(Duration.millis(durationMs - WRAP_LEAD_MS))
                }
                stimulusPause(QUIET_MS)
            }
            Probe.B4 -> {
                fx { player.rate = rate }
                stimulusPause(QUIET_MS)
            }
            Probe.B4_CONTROL -> {
                // Rewound every round so that the write, 500 ms into playing, is far from a loop wrap (except at 3x).
                fx {
                    player.rate = 1.0
                    player.seek(Duration.ZERO)
                }
                stimulusPause(QUIET_MS)
            }
            else -> Unit
        }
        return true
    }

    /**
     * The speed of the playhead over [SLOPE_WINDOW_MS] of producer time: milliseconds of media per millisecond of wall
     * time, taken from the `currentTime` events as they are published on the FX thread (a decrease, a loop wrap, adds
     * nothing). 0.0 when fewer than two events arrive.
     */
    private fun measureSlope(player: MediaPlayer): Double {
        val samples: MutableList<Pair<Long, Double>> = mutableListOf()
        val spanned = CountDownLatch(1)
        val listener: ChangeListener<Duration> = object : ChangeListener<Duration> {
            override fun changed(observable: ObservableValue<out Duration>, old: Duration?, new: Duration?) {
                // timing-hygiene-ignore: TH2 producer the stamp of a currentTime event taken on the FX thread that publishes it, to turn events into a speed
                val stamp: Long = System.nanoTime()
                samples += stamp to (new?.toMillis() ?: return)
                if (stamp - samples.first().first >= SLOPE_WINDOW_MS * NANOS_PER_MS) spanned.countDown()
            }
        }
        fx { player.currentTimeProperty().addListener(listener) }
        spanned.await(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
        fx { player.currentTimeProperty().removeListener(listener) }
        val taken: List<Pair<Long, Double>> = fx { samples.toList() }
        if (taken.size < 2) return 0.0
        val elapsedMs: Double = (taken.last().first - taken.first().first).toDouble() / NANOS_PER_MS
        if (elapsedMs <= 0.0) return 0.0
        var movedMs: Double = 0.0
        for (i: Int in 1 until taken.size) movedMs += maxOf(0.0, taken[i].second - taken[i - 1].second)
        return movedMs / elapsedMs
    }

    /** FROZEN at a slope of [FROZEN_SLOPE] or less, else the nearer of 1x and [rate], OTHER_SLOPE when neither is within 30 percent. */
    private fun classifySlope(slope: Double, rate: Double): Outcome = when {
        slope <= FROZEN_SLOPE -> Outcome.FROZEN
        kotlin.math.abs(slope - 1.0) <= SLOPE_TOLERANCE && kotlin.math.abs(slope - 1.0) <= kotlin.math.abs(slope - rate) -> Outcome.ONE_X
        kotlin.math.abs(slope - rate) <= SLOPE_TOLERANCE * rate -> Outcome.AT_RATE
        else -> Outcome.OTHER_SLOPE
    }

    /** After the stimulus and a settle pause: MOVED when the playhead steps within the bound, else FROZEN while PLAYING. */
    private fun observe(player: MediaPlayer): Outcome {
        stimulusPause(SETTLE_MS)
        if (awaitMoving(player, FROZEN_BOUND_MS)) return Outcome.MOVED
        val status: MediaPlayer.Status = fx { player.status }
        return if (status == MediaPlayer.Status.PLAYING) Outcome.FROZEN else Outcome.NOT_PLAYING
    }

    private fun create(subject: Subject, clipUri: String) {
        val ready = CountDownLatch(1)
        fx {
            val media = Media(clipUri)
            val player = MediaPlayer(media)
            player.cycleCount = MediaPlayer.INDEFINITE
            player.setOnReady { ready.countDown() }
            subject.player = player
        }
        check(ready.await(READY_BOUND_MS, TimeUnit.MILLISECONDS)) { "${subject.probe.label}: the video clip was not READY in $READY_BOUND_MS ms" }
    }

    private fun replace(subject: Subject, clipUri: String) {
        val old: MediaPlayer = subject.player
        fx {
            old.pause()
        }
        subject.replaced += 1
        create(subject, clipUri)
        // The old player is left paused and not disposed (CoreVideo window); the process exit releases it.
    }

    /** True once [player] has [target] as its status, within [boundMs]. */
    private fun awaitStatus(player: MediaPlayer, target: MediaPlayer.Status, boundMs: Long = STATUS_BOUND_MS): Boolean {
        val reached = CountDownLatch(1)
        val listener: ChangeListener<MediaPlayer.Status> = object : ChangeListener<MediaPlayer.Status> {
            override fun changed(observable: ObservableValue<out MediaPlayer.Status>, old: MediaPlayer.Status?, new: MediaPlayer.Status?) {
                if (new == target) reached.countDown()
            }
        }
        fx {
            if (player.status == target) reached.countDown() else player.statusProperty().addListener(listener)
        }
        val result: Boolean = reached.await(boundMs, TimeUnit.MILLISECONDS)
        fx { player.statusProperty().removeListener(listener) }
        return result
    }

    /** True when `currentTime` takes a value other than its value now, within [boundMs]; the listener runs on the FX thread. */
    private fun awaitMoving(player: MediaPlayer, boundMs: Long = MOVING_BOUND_MS): Boolean {
        val moved = CountDownLatch(1)
        var baseline: Duration = Duration.ZERO
        val listener: ChangeListener<Duration> = object : ChangeListener<Duration> {
            override fun changed(observable: ObservableValue<out Duration>, old: Duration?, new: Duration?) {
                if (new != baseline) moved.countDown()
            }
        }
        fx {
            baseline = player.currentTime
            player.currentTimeProperty().addListener(listener)
        }
        val result: Boolean = moved.await(boundMs, TimeUnit.MILLISECONDS)
        fx { player.currentTimeProperty().removeListener(listener) }
        return result
    }

    /** Runs [block] on the FX thread and returns its result; fails with the block's own exception or at the hang bound. */
    private fun <T> fx(block: () -> T): T {
        val result = CompletableFuture<T>()
        Platform.runLater {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
    }

    /** The probes' only real-time pause: a gap between two stimuli, which is the independent variable of the probe. */
    private fun stimulusPause(ms: Long) {
        if (ms <= 0L) return
        // timing-hygiene-ignore: TH1 inject the probe stimulus gap (the independent variable of the hazard probes, never a wait for a condition)
        Thread.sleep(ms)
    }

    private companion object {
        const val HAZARDS_PROPERTY: String = "kmptoolkit.test.hazards"
        const val ROUNDS: Int = 40
        const val SETTLE_MS: Long = 300L
        const val PLAYED_MS: Long = 500L
        const val CONTROL_GAP_MS: Long = 400L
        const val MAX_SEEK_RUN_GAP_MS: Long = 50L
        const val FROZEN_BOUND_MS: Long = 2_000L
        const val MOVING_BOUND_MS: Long = 3_000L
        const val STATUS_BOUND_MS: Long = 3_000L
        const val READY_BOUND_MS: Long = 15_000L
        const val HANG_BOUND_MS: Long = 15_000L
        val RATES: List<Double> = listOf(2.0, 0.5, 3.0, 1.5)
        const val QUIET_MS: Long = 400L
        const val RESEEK_FROM_MS: Double = 1_800.0
        const val WRAP_LEAD_MS: Double = 500.0
        const val WRAP_BOUND_MS: Long = 3_000L
        const val W_PLAY_RANGE_MS: Long = 150L
        const val W_NEAR_RANGE_MS: Long = 20L
        const val W_IDLE_8X_RANGE_MS: Long = 100L
        const val EIGHT_X: Double = 8.0
        const val W_IDLE_RANGE_MS: Long = 300L
        const val W_PLAY_CONTROL_GAP_MS: Long = 500L
        const val W_IDLE_CONTROL_GAP_MS: Long = 600L
        const val W_WRAP_CONTROL_GAP_MS: Long = 400L
        const val SLOPE_WINDOW_MS: Long = 500L
        const val NANOS_PER_MS: Long = 1_000_000L
        const val FROZEN_SLOPE: Double = 0.1
        const val SLOPE_TOLERANCE: Double = 0.3
        val SEEK_PROBES: List<Probe> = listOf(Probe.SEEK_RUN, Probe.NUDGE, Probe.NEARSTART, Probe.CONTROL, Probe.FIRSTPLAY)
        val EIGHT_X_PROBES: Set<Probe> = setOf(Probe.W_IDLE_8X, Probe.W_IDLE_8X_CONTROL)
        val SLOPE_PROBES: Set<Probe> = setOf(Probe.B4, Probe.B4_CONTROL)
        val WINDOW_PROBES: Set<Probe> = setOf(
            Probe.W_PLAY, Probe.W_PLAY_CONTROL, Probe.W_IDLE, Probe.W_IDLE_CONTROL,
            Probe.W_PLAY_NEAR, Probe.W_IDLE_8X, Probe.W_IDLE_8X_CONTROL, Probe.W_WRAP, Probe.W_WRAP_CONTROL, Probe.B4, Probe.B4_CONTROL,
        )
    }
}
