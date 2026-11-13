package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * Four probes of 40 rounds each, one real video `MediaPlayer` per probe (never disposed per round: a video player
 * disposed while its CoreVideo display link starts can kill the JVM, see docs/kmptoolkit-video-player-javafx/05-platform-notes.md,
 * "Known issue"), rounds interleaved SEEK-RUN, NUDGE, NEARSTART, CONTROL so that drift of the machine hits all four alike:
 *  - SEEK-RUN: PLAYING for 500 ms, `seek(0)`, then a rate change 0 to 50 ms later (the D2-PF1 window);
 *  - NUDGE: PAUSED, `seek(0)`, `play()`, then `rate = 1.0; rate = r` two FX tasks later (`giveNudge`);
 *  - NEARSTART: PAUSED at 0, `seek(currentTime)`, `play()`, `rate = 1.0; rate = r` in the same FX task (`nearStart`);
 *  - CONTROL: SEEK-RUN with 400 ms between the seek and the rate change.
 *
 * "Frozen" means: after the stimulus and a settle pause of [SETTLE_MS], status PLAYING and `currentTime` unchanged for
 * [FROZEN_BOUND_MS]. The only assertion is harness sanity (every probe reached PLAYING and moved at least once); the
 * result is the printed table `HAZARD <probe> frozen=k/40`. The measurement is event driven (a `currentTime` listener
 * armed on the FX thread); the only real-time pauses are the stimulus gaps, which are the independent variable.
 */
class JavaFxNativeHazardCharacterizationTest {

    private enum class Probe(val label: String) {
        SEEK_RUN("SEEK-RUN"),
        NUDGE("NUDGE"),
        NEARSTART("NEARSTART"),
        CONTROL("CONTROL"),
    }

    private enum class Outcome { MOVED, FROZEN, NOT_PLAYING }

    private class Subject(val probe: Probe) {
        lateinit var player: MediaPlayer
        var replaced: Int = 0
        var reachedPlaying: Boolean = false
        val outcomes: MutableMap<Outcome, Int> = Outcome.values().associateWith { 0 }.toMutableMap()
        val frozenRounds: MutableList<String> = mutableListOf()
        val setupFailures: MutableList<String> = mutableListOf()
    }

    @Test
    fun `the native rate freeze windows are measured on real media and printed`() {
        Assume.assumeTrue(
            "opt-in probes: run with -Pkmptoolkit.test.hazards=true",
            System.getProperty(HAZARDS_PROPERTY) == "true",
        )
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val clipUri: String = TestClip.copyToTempFile().toURI().toString()

        val subjects: List<Subject> = Probe.values().map { Subject(it) }
        subjects.forEach { create(it, clipUri) }

        for (round: Int in 0 until ROUNDS) {
            for (subject: Subject in subjects) runRound(subject, round, clipUri)
        }

        // Not disposed: the process exit releases the four players; a video player disposed right after its display
        // link ran is the documented crash window.
        for (subject: Subject in subjects) {
            val frozen: Int = subject.outcomes.getValue(Outcome.FROZEN)
            println("HAZARD ${subject.probe.label} frozen=$frozen/$ROUNDS")
            println(
                "HAZARD-DETAIL ${subject.probe.label} moved=${subject.outcomes.getValue(Outcome.MOVED)} " +
                    "notPlaying=${subject.outcomes.getValue(Outcome.NOT_PLAYING)} replacedPlayers=${subject.replaced} " +
                    "setupFailures=${subject.setupFailures.size}",
            )
            subject.frozenRounds.forEach { println("HAZARD-FROZEN ${subject.probe.label} $it") }
            subject.setupFailures.forEach { println("HAZARD-SETUP ${subject.probe.label} $it") }
        }
        for (subject: Subject in subjects) {
            assertTrue(subject.reachedPlaying, "${subject.probe.label}: the player never reached PLAYING with a moving playhead")
        }
    }

    private fun runRound(subject: Subject, round: Int, clipUri: String) {
        val rate: Double = RATES[round % RATES.size]
        var ready: Boolean = setup(subject)
        if (!ready) {
            // A player left frozen by an earlier round that pause, seek and play cannot revive: replace it once.
            subject.setupFailures += "round $round: setup failed on the player in use, replacing it"
            replace(subject, clipUri)
            ready = setup(subject)
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
            Probe.NEARSTART -> fx {
                player.seek(player.currentTime)
                player.play()
                player.rate = 1.0
                player.rate = rate
            }
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
    private fun setup(subject: Subject): Boolean {
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
            Probe.NUDGE, Probe.NEARSTART -> Unit
        }
        return true
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
    }
}
