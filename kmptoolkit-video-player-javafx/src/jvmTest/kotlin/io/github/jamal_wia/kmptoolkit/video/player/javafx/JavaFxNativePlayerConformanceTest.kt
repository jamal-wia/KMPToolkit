package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javafx.application.Platform
import javafx.scene.media.MediaPlayer
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume

/**
 * Tier C of the JavaFX playback port (task J14a, plans/javafx-port-design.md section 5.3, plans/javafx-port-ruling.md):
 * one test per port promise, C1 to C12, on the real adapter ([JavaFxBackend] and [JavaFxNativePlayer], `MediaPlayer` underneath), never on the scripted fake. The
 * scripted fake of the model tier claims the same behaviours (B1 to B13); these tests are what keeps those claims
 * honest against real media.
 *
 * Every assertion is derived from the port contract (`PlaybackPort.kt` KDoc and the design table), not from what the
 * adapter happens to print. Every wait is on an event with a deadline: a [Watch] completed by the callback itself, or a
 * latch; there is no sleep. Time stamps are taken on the FX thread in the very callback that produces a sample
 * (producer side), never read on the test thread after a pause. A check is stated as the contract states it, not as the
 * design table first wrote it: C11 asserts that a source that is not media never becomes READY (refused by `probe`, or
 * silent, an error report being allowed), because on macOS no such source reaches an error handler (the
 * `FxPlayback.open` KDoc; J14a report, Question 1; plans/j14a-c11-advice.md).
 *
 * C5 carries the FX liveness void rule (determinism plan 2.4, ruling 4.1): an independent probe times a
 * `Platform.runLater` round trip all through a measuring window, and a window in which the FX thread itself was
 * starved is voided and measured again (at most [MAX_WINDOWS] windows), never counted against the port.
 *
 * Video players are never disposed except in C9, which does it once, after the player has played (the CoreVideo
 * dispose race of docs/kmptoolkit-video-player-javafx/05-platform-notes.md, "Known issue"); every other test leaves
 * its player paused.
 */
class JavaFxNativePlayerConformanceTest {

    // ---- what the callbacks record -------------------------------------------------------------------------------

    /** A one-shot result a callback hands to the test thread; [await] fails with its own message at the deadline. */
    private class Watch<T : Any> {
        val future: CompletableFuture<T> = CompletableFuture()

        fun await(boundMs: Long, what: () -> String): T = try {
            future.get(boundMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("Timed out after $boundMs ms: ${what()}")
        }
    }

    private data class TimeSample(val valueMs: Double, val nanos: Long, val status: NativeStatus)

    /** One `onCycleDecision`: JavaFX's `currentCount` as it stood in that callback, and how many `onEndOfMedia` had been reported before it. */
    private data class CycleDecision(val currentCount: Int, val endsBefore: Int)

    private data class EndReading(val status: NativeStatus, val currentTimeMs: Double, val samplesBefore: Int)

    private data class ReadyReading(
        val status: NativeStatus,
        val durationMs: Double,
        val width: Int,
        val height: Int,
        val durationEventsSoFar: Int,
        val sizeEventsSoFar: Int,
    )

    /** The [NativeEvents] of a test: every callback checks its thread and records what the port promised to carry. */
    private class Recorder : NativeEvents {
        /** Set before the events are installed; read only inside the callbacks, on the FX thread. */
        lateinit var player: JavaFxNativePlayer

        val offFx: MutableList<String> = CopyOnWriteArrayList()
        val samples: MutableList<TimeSample> = CopyOnWriteArrayList()
        val statuses: MutableList<NativeStatus> = CopyOnWriteArrayList()
        val ends: MutableList<EndReading> = CopyOnWriteArrayList()
        val cycleDecisions: MutableList<CycleDecision> = CopyOnWriteArrayList()
        val errors: MutableList<String> = CopyOnWriteArrayList()
        val durationEvents: AtomicInteger = AtomicInteger()
        val sizeEvents: AtomicInteger = AtomicInteger()
        val pulses: AtomicInteger = AtomicInteger()

        @Volatile
        var onPulseAction: ((Long) -> Unit)? = null

        val readyWatch: Watch<ReadyReading> = Watch()
        private val statusWatches: MutableList<Pair<NativeStatus, Watch<NativeStatus>>> = CopyOnWriteArrayList()
        private val timeWatches: MutableList<Pair<(TimeSample) -> Boolean, Watch<TimeSample>>> = CopyOnWriteArrayList()
        private val endWatches: MutableList<Pair<Int, Watch<EndReading>>> = CopyOnWriteArrayList()

        /** Registered before the command that should cause [status], so the callback cannot be missed. */
        fun watchStatus(status: NativeStatus): Watch<NativeStatus> = Watch<NativeStatus>().also { statusWatches += status to it }

        /** The first `currentTime` sample from now on that [accept] takes. */
        fun watchTime(accept: (TimeSample) -> Boolean): Watch<TimeSample> = Watch<TimeSample>().also { timeWatches += accept to it }

        /** The [n]th `onEndOfMedia` of this player (counted from its creation). */
        fun watchEnd(n: Int): Watch<EndReading> = Watch<EndReading>().also { endWatches += n to it }

        private fun onFx(name: String) {
            if (!Platform.isFxApplicationThread()) offFx += name
        }

        override fun onReady() {
            onFx("onReady")
            readyWatch.future.complete(
                ReadyReading(player.status, player.durationMs, player.width, player.height, durationEvents.get(), sizeEvents.get()),
            )
        }

        override fun onPlayerError() {
            onFx("onPlayerError")
            errors += "onPlayerError: ${player.playerError ?: player.mediaError}"
        }

        override fun onMediaError() {
            onFx("onMediaError")
            errors += "onMediaError: ${player.mediaError ?: player.playerError}"
        }

        override fun onCycleDecision() {
            onFx("onCycleDecision")
            cycleDecisions += CycleDecision(player.mediaPlayer.currentCount, ends.size)
        }

        override fun onEndOfMedia() {
            onFx("onEndOfMedia")
            val reading = EndReading(player.status, player.currentTimeMs, samples.size)
            ends += reading
            endWatches.filter { it.first <= ends.size }.forEach { it.second.future.complete(reading) }
        }

        override fun onStatus(status: NativeStatus) {
            onFx("onStatus")
            statuses += status
            statusWatches.filter { it.first == status }.forEach { it.second.future.complete(status) }
        }

        override fun onCurrentTime() {
            onFx("onCurrentTime")
            val sample = TimeSample(player.currentTimeMs, producerNanos(), player.status)
            samples += sample
            timeWatches.filter { it.first(sample) }.forEach { it.second.future.complete(sample) }
        }

        override fun onBufferProgress() = onFx("onBufferProgress")

        override fun onDuration() {
            onFx("onDuration")
            durationEvents.incrementAndGet()
        }

        override fun onSize() {
            onFx("onSize")
            sizeEvents.incrementAndGet()
        }

        override fun onPulse(nowNanos: Long) {
            onFx("onPulse")
            pulses.incrementAndGet()
            onPulseAction?.invoke(nowNanos)
        }
    }

    private class Rig(val native: JavaFxNativePlayer, val events: Recorder) {
        @Volatile
        var disposed: Boolean = false
    }

    /**
     * An independent probe of the FX thread: every [PROBE_PERIOD_MS] a `Platform.runLater` round trip is posted from a
     * thread of the probe's own and timed on both ends. [stop] answers the worst latency seen, counting a task still
     * waiting at that moment. It does not depend on the port, so a starved FX thread cannot hide behind it.
     */
    private class FxLivenessProbe {
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "fx-liveness-probe").apply { isDaemon = true }
        }
        private val pending: ConcurrentLinkedQueue<Long> = ConcurrentLinkedQueue()
        private val worstNanos: AtomicLong = AtomicLong(0L)

        fun start() {
            executor.scheduleAtFixedRate(
                {
                    val sent: Long = producerNanos()
                    pending.add(sent)
                    Platform.runLater {
                        pending.poll()
                        val latency: Long = producerNanos() - sent
                        worstNanos.accumulateAndGet(latency) { a: Long, b: Long -> maxOf(a, b) }
                    }
                },
                0L,
                PROBE_PERIOD_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        fun stop(): Double {
            executor.shutdownNow()
            val oldest: Long? = pending.peek()
            val waiting: Long = if (oldest == null) 0L else producerNanos() - oldest
            return maxOf(worstNanos.get(), waiting) / 1_000_000.0
        }
    }

    private val rigs: MutableList<Rig> = CopyOnWriteArrayList()

    /** Leaves no player playing: paused and without a pulse, so a late end or a late snapshot cannot reach the next test. */
    @AfterTest
    fun quiesce() {
        for (rig: Rig in rigs) {
            if (rig.disposed) continue
            try {
                fx {
                    rig.native.pause()
                    rig.native.stopPulse()
                }
            } catch (_: Throwable) {
                // The test already failed or the toolkit is gone; the original failure is the one to read.
            }
        }
        rigs.clear()
    }

    // ---- C1 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C1 a rate write of the same value fires no change and a different value fires exactly one`() {
        val rig: Rig = openReady()
        val mediaPlayer: MediaPlayer = rig.native.mediaPlayer
        val rateChanges = AtomicInteger()
        val volumeChanges = AtomicInteger()
        fx {
            mediaPlayer.rateProperty().addListener { _, _, _ -> rateChanges.incrementAndGet() }
            mediaPlayer.volumeProperty().addListener { _, _, _ -> volumeChanges.incrementAndGet() }
        }

        val rate: Double = fx { rig.native.rate }
        fx { rig.native.rate = rate }
        assertEquals(0, rateChanges.get(), "a write of the rate the property already holds ($rate) changed it")

        val other: Double = if (rate == 2.0) 0.5 else 2.0
        fx { rig.native.rate = other }
        assertEquals(1, rateChanges.get(), "a write of $other over $rate must change the rate exactly once")
        assertEquals(other, fx { rig.native.rate }, "the rate reads back what was written")

        fx { rig.native.rate = other }
        assertEquals(1, rateChanges.get(), "a second write of the same rate $other changed it again")

        fx { rig.native.rate = rate }
        assertEquals(2, rateChanges.get(), "writing back $rate must change the rate exactly once more")

        val volume: Double = fx { rig.native.volume }
        fx { rig.native.volume = volume }
        assertEquals(0, volumeChanges.get(), "a write of the volume the property already holds ($volume) changed it")
        val quieter: Double = if (volume == 0.0) 0.5 else 0.0
        fx { rig.native.volume = quieter }
        assertEquals(1, volumeChanges.get(), "a write of $quieter over $volume must change the volume exactly once")
        assertEquals(quieter, fx { rig.native.volume }, "the volume reads back what was written")
        fx { rig.native.volume = quieter }
        assertEquals(1, volumeChanges.get(), "a second write of the same volume $quieter changed it again")
    }

    // ---- C2 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C2 play reaches PLAYING within two seconds and onStatus reports it on the FX thread`() {
        val rig: Rig = openReady()
        val playing: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PLAYING)
        val started: Int = rig.events.statuses.size

        fx { rig.native.play() }
        playing.await(STATUS_BOUND_MS) {
            "play() did not report PLAYING; statuses after READY: ${rig.events.statuses.drop(started)}, now ${fx { rig.native.status }}"
        }

        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
        assertEquals(emptyList(), rig.events.errors, "a healthy clip reported an error")
        assertEquals(NativeStatus.PLAYING, fx { rig.native.status }, "status after the PLAYING callback")
        assertTrue(NativeStatus.PLAYING in rig.events.statuses, "onStatus(PLAYING) was not delivered")
    }

    // ---- C3 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C3 pause after play reaches PAUSED and onStatus reports it on the FX thread`() {
        val rig: Rig = openReady()
        val playing: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PLAYING)
        fx { rig.native.play() }
        playing.await(STATUS_BOUND_MS) { "play() did not report PLAYING" }

        val paused: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PAUSED)
        fx { rig.native.pause() }
        paused.await(STATUS_BOUND_MS) { "pause() did not report PAUSED; statuses: ${rig.events.statuses}" }

        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
        assertEquals(NativeStatus.PAUSED, fx { rig.native.status }, "status after the PAUSED callback")
    }

    // ---- C4 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C4 a seek while paused publishes the key frame at or before the target and a seek near the start publishes 0`() {
        val rig: Rig = openReady()
        val playing: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PLAYING)
        fx { rig.native.play() }
        playing.await(STATUS_BOUND_MS) { "play() did not report PLAYING" }
        val paused: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PAUSED)
        fx { rig.native.pause() }
        paused.await(STATUS_BOUND_MS) { "pause() did not report PAUSED" }
        val pausedAt: Double = fx { rig.native.currentTimeMs }
        assertTrue(pausedAt < SEEK_TARGET_MS - KEY_FRAME_INTERVAL_MS + 1.0, "the player ran past the key frame before the seek ($pausedAt ms): the test cannot tell a seek from the run")

        val landed: Watch<TimeSample> = rig.events.watchTime { abs(it.valueMs - pausedAt) > 50.0 }
        fx { rig.native.seek(SEEK_TARGET_MS) }
        val sample: TimeSample = landed.await(SEEK_BOUND_MS) {
            "no currentTime away from $pausedAt after seek($SEEK_TARGET_MS); samples ${rig.events.samples.map { it.valueMs }}"
        }
        assertTrue(
            sample.valueMs >= KEY_FRAME_INTERVAL_MS && sample.valueMs <= SEEK_TARGET_MS,
            "seek($SEEK_TARGET_MS) while paused published ${sample.valueMs} ms: the key frame at or before the target " +
                "($KEY_FRAME_INTERVAL_MS to $SEEK_TARGET_MS) was expected; samples ${rig.events.samples.map { it.valueMs }}",
        )

        val atStart: Watch<TimeSample> = rig.events.watchTime { abs(it.valueMs - sample.valueMs) > 50.0 }
        fx { rig.native.seek(NEAR_START_TARGET_MS) }
        val first: TimeSample = atStart.await(SEEK_BOUND_MS) {
            "no currentTime away from ${sample.valueMs} after seek($NEAR_START_TARGET_MS); samples ${rig.events.samples.map { it.valueMs }}"
        }
        assertEquals(
            0.0,
            first.valueMs,
            1.0,
            "a seek to $NEAR_START_TARGET_MS ms (within 100 ms of the start) must publish 0; samples ${rig.events.samples.map { it.valueMs }}",
        )
        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
    }

    // ---- C5 ------------------------------------------------------------------------------------------------------

    private sealed interface Window {
        data class Measured(val values: List<Double>, val maxGapMs: Double) : Window
        data class Voided(val why: String) : Window
    }

    @Test
    fun `C5 currentTime events arrive while playing with no gap of more than one second on a live FX thread`() {
        val voids: MutableList<String> = mutableListOf()
        for (attempt: Int in 1..MAX_WINDOWS) {
            when (val window: Window = measureWindow()) {
                is Window.Measured -> {
                    assertTrue(
                        window.values.size >= MIN_EVENTS_IN_WINDOW,
                        "only ${window.values.size} currentTime events while playing from the first to $WINDOW_END_MS ms: ${window.values}",
                    )
                    assertTrue(
                        window.maxGapMs <= MAX_EVENT_GAP_MS,
                        "currentTime events had a gap of ${window.maxGapMs} ms (limit $MAX_EVENT_GAP_MS ms) on a live FX thread; values ${window.values}",
                    )
                    return
                }
                is Window.Voided -> voids += "window $attempt: ${window.why}"
            }
        }
        fail("the FX thread was starved in all $MAX_WINDOWS windows, so nothing was measured (not a port failure): $voids")
    }

    /** One measuring window on a fresh player; a window with a starved FX thread comes back [Window.Voided]. */
    private fun measureWindow(): Window {
        val rig: Rig = openReady()
        val probe = FxLivenessProbe()
        var worstLatencyMs: Double = Double.NaN
        probe.start()
        var failure: String? = null
        var first: TimeSample? = null
        var last: TimeSample? = null
        try {
            val firstWatch: Watch<TimeSample> = rig.events.watchTime { true }
            val endWatch: Watch<TimeSample> = rig.events.watchTime { it.valueMs >= WINDOW_END_MS }
            fx { rig.native.play() }
            try {
                first = firstWatch.await(HANG_BOUND_MS) { "no currentTime event after play()" }
                last = endWatch.await(HANG_BOUND_MS) { "the playhead never reached $WINDOW_END_MS ms; samples ${rig.events.samples.map { it.valueMs }}" }
            } catch (e: AssertionError) {
                failure = e.message
            }
        } finally {
            worstLatencyMs = probe.stop()
        }
        if (worstLatencyMs > STARVED_LATENCY_MS) {
            return Window.Voided("FX round trip took up to $worstLatencyMs ms (bound $STARVED_LATENCY_MS ms)")
        }
        failure?.let { fail("$it (the FX thread stayed live, so this is the port)") }
        val from: Long = checkNotNull(first).nanos
        val to: Long = checkNotNull(last).nanos
        val inWindow: List<TimeSample> = rig.events.samples.filter { it.nanos in from..to }
        val gaps: List<Double> = inWindow.zipWithNext { a: TimeSample, b: TimeSample -> (b.nanos - a.nanos) / 1_000_000.0 }
        return Window.Measured(inWindow.map { it.valueMs }, gaps.maxOrNull() ?: 0.0)
    }

    // ---- C6 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C6 without looping onEndOfMedia fires once and the status stays PLAYING at the duration`() {
        val rig: Rig = openReady()
        assertEquals(1, fx { rig.native.cycleCount }, "a new player plays one cycle")
        val duration: Double = fx { rig.native.durationMs }
        val firstEnd: Watch<EndReading> = rig.events.watchEnd(1)
        val secondEnd: Watch<EndReading> = rig.events.watchEnd(2)

        fx { rig.native.play() }
        val end: EndReading = firstEnd.await(END_BOUND_MS) { "no onEndOfMedia; samples ${rig.events.samples.map { it.valueMs }}" }

        assertEquals(NativeStatus.PLAYING, end.status, "JavaFX leaves the status at PLAYING when the last cycle ends")
        assertEquals(duration, end.currentTimeMs, 1.0, "currentTime at the end of media")
        // Absence is proved by waiting on the event that must not come, with a deadline.
        try {
            secondEnd.future.get(NO_SECOND_END_MS, TimeUnit.MILLISECONDS)
            fail("a second onEndOfMedia fired without looping: ${rig.events.ends}")
        } catch (_: TimeoutException) {
            // none, as the contract says
        }
        assertEquals(1, rig.events.ends.size, "onEndOfMedia count without looping")
        assertEquals(NativeStatus.PLAYING, fx { rig.native.status }, "status after the end of media")
        assertEquals(duration, fx { rig.native.currentTimeMs }, 1.0, "currentTime stays at the duration")
        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
    }

    // ---- C7 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C7 with looping onEndOfMedia fires for every cycle and the position wraps`() {
        val rig: Rig = openReady()
        fx { rig.native.cycleCount = INDEFINITE }
        assertEquals(INDEFINITE, fx { rig.native.cycleCount }, "cycleCount reads back what was written")
        assertEquals(MediaPlayer.INDEFINITE, INDEFINITE, "the port's INDEFINITE is the value of MediaPlayer.INDEFINITE")
        val duration: Double = fx { rig.native.durationMs }
        val secondEnd: Watch<EndReading> = rig.events.watchEnd(2)

        fx { rig.native.play() }
        secondEnd.await(LOOP_BOUND_MS) { "fewer than 2 onEndOfMedia while looping; ends ${rig.events.ends}" }

        val firstEnd: EndReading = rig.events.ends.first()
        val afterFirstEnd: List<Double> = rig.events.samples.drop(firstEnd.samplesBefore).map { it.valueMs }
        assertTrue(
            afterFirstEnd.any { it < duration / 2.0 },
            "the position did not wrap to the start after the first end of media; samples after it $afterFirstEnd",
        )
        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
    }

    // ---- C8 ------------------------------------------------------------------------------------------------------

    private data class BeforeReady(val durationMs: Double)

    @Test
    fun `C8 the duration is NaN or known before ready and known after it with the width and height`() {
        val (rig: Rig, before: BeforeReady) = create(clipUri()) { BeforeReady(it.durationMs) }
        assertTrue(
            before.durationMs.isNaN() || abs(before.durationMs - TestClip.DURATION_MS) <= DURATION_TOLERANCE_MS,
            "durationMs before ready must be NaN or the clip's ${TestClip.DURATION_MS} ms, was ${before.durationMs}",
        )

        val ready: ReadyReading = rig.events.readyWatch.await(READY_BOUND_MS) { "the clip was not READY" }
        assertEquals(NativeStatus.READY, ready.status, "status inside onReady")
        assertEquals(TestClip.DURATION_MS.toDouble(), ready.durationMs, DURATION_TOLERANCE_MS, "durationMs after ready")
        assertEquals(TestClip.WIDTH, ready.width, "width after ready")
        assertEquals(TestClip.HEIGHT, ready.height, "height after ready")
        if (before.durationMs.isNaN()) {
            assertTrue(ready.durationEventsSoFar >= 1, "the duration was unknown at creation and became known with no onDuration")
        }
        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
    }

    // ---- C9 ------------------------------------------------------------------------------------------------------

    @Test
    fun `C9 the dispose sequence runs without an exception and leaves DISPOSED`() {
        val rig: Rig = openReady()
        val pulsed = CompletableFuture<Unit>()
        rig.events.onPulseAction = { pulsed.complete(Unit) }
        fx { rig.native.startPulse() }
        pulsed.get(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
        val playing: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PLAYING)
        val moved: Watch<TimeSample> = rig.events.watchTime { true }
        fx { rig.native.play() }
        playing.await(STATUS_BOUND_MS) { "play() did not report PLAYING" }
        moved.await(HANG_BOUND_MS) { "the playhead never moved before the dispose" }
        val paused: Watch<NativeStatus> = rig.events.watchStatus(NativeStatus.PAUSED)
        fx { rig.native.pause() }
        paused.await(STATUS_BOUND_MS) { "pause() did not report PAUSED" }

        val thrown: MutableList<Throwable> = CopyOnWriteArrayList()
        val previous: Thread.UncaughtExceptionHandler? = fx {
            val thread: Thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.also {
                thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> thrown += e }
            }
        }
        try {
            // The order FxPlayback.dispose uses: four separate port calls, then the dispose itself.
            rig.disposed = true
            val pulsesAtDispose: Int = fx {
                rig.native.stopPulse()
                rig.native.detachView()
                rig.native.clearEndOfMedia()
                rig.native.cycleCount = 1
                rig.native.dispose()
                rig.events.pulses.get()
            }
            repeat(DRAIN_ROUND_TRIPS) { fx { } }

            assertEquals(NativeStatus.DISPOSED, fx { rig.native.status }, "status after the dispose sequence and ${DRAIN_ROUND_TRIPS} FX round trips")
            assertEquals(emptyList(), thrown, "exceptions that reached the FX thread during and after the dispose")
            assertEquals(pulsesAtDispose, rig.events.pulses.get(), "the pulse kept firing after stopPulse")
            assertEquals(0, rig.events.ends.size, "an end of media was reported by a player that never reached the end")
        } finally {
            fx { Thread.currentThread().uncaughtExceptionHandler = previous }
        }
    }

    // ---- C10 -----------------------------------------------------------------------------------------------------

    private data class FirstSnapshot(val centreAlpha: Int, val untouchedSentinels: Int, val tailIntact: Boolean)

    @Test
    fun `C10 snapshotInto writes a transparent picture before the first one and an opaque red centre after it`() {
        val width: Int = TestClip.WIDTH
        val height: Int = TestClip.HEIGHT
        val centre: Int = (height / 2) * width + width / 2

        // Inside the very FX task that creates the player: nothing can have been decoded yet.
        val (rig: Rig, before: FirstSnapshot) = create(clipUri()) { native ->
            val pixels: IntArray = sentinelArray(width * height)
            native.snapshotInto(pixels, width, height)
            FirstSnapshot(pixels[centre] ushr 24, pixels.take(width * height).count { it == SENTINEL }, tailIsIntact(pixels, width * height))
        }
        assertEquals(0, before.centreAlpha, "the snapshot before the first picture must be transparent at the centre")
        assertEquals(0, before.untouchedSentinels, "the snapshot before the first picture left ${before.untouchedSentinels} entries of the caller's array unwritten")
        assertTrue(before.tailIntact, "the snapshot wrote past width x height entries of the caller's array")

        rig.events.readyWatch.await(READY_BOUND_MS) { "the clip was not READY" }
        val picture = Watch<IntArray>()
        val tailOk = AtomicInteger(0)
        rig.events.onPulseAction = {
            if (!picture.future.isDone) {
                val pixels: IntArray = sentinelArray(width * height)
                rig.native.snapshotInto(pixels, width, height)
                if (!tailIsIntact(pixels, width * height)) tailOk.incrementAndGet()
                if (pixels[centre] ushr 24 == 0xFF) picture.future.complete(pixels)
            }
        }
        fx { rig.native.startPulse() }
        val pixels: IntArray = picture.await(PICTURE_BOUND_MS) { "no opaque centre pixel in ${PICTURE_BOUND_MS} ms of pulses" }

        val argb: Int = pixels[centre]
        val red: Int = (argb shr 16) and 0xFF
        val green: Int = (argb shr 8) and 0xFF
        val blue: Int = argb and 0xFF
        assertTrue(red >= 200 && green <= 80 && blue <= 80, "the centre of the solid red clip decoded as ${"%08X".format(argb)}")
        assertEquals(0, pixels.take(width * height).count { it == SENTINEL }, "the snapshot left entries of the caller's array unwritten")
        assertEquals(0, tailOk.get(), "a snapshot wrote past width x height entries of the caller's array")
        assertEquals(emptyList(), rig.events.offFx, "callbacks that ran off the FX thread")
    }

    // ---- C11 -----------------------------------------------------------------------------------------------------

    private class Hostile(val name: String, val rig: Rig)

    /**
     * C11 (B9): a source that is not media never becomes READY. `probe` refuses it, or the player is created and reports
     * no `onReady` and no status beyond UNKNOWN or HALTED; an error report is allowed, not required, because macOS
     * reports nothing for a file it cannot parse (the `FxPlayback.open` KDoc) while another backend may report an error.
     * A `.txt` is refused by `probe` (05-platform-notes: an unknown container fails `prepare` straight away). The
     * never-ready players then survive the dispose sequence `open` runs after its ready timeout.
     *
     * The wait is on the event that must not come: one fixed [NO_READY_WINDOW_MS] window, opened after the checked-in
     * clip, created after every hostile player, became READY. Every red is a positive observation (a READY, a loaded
     * status, a throw, a callback off the FX thread), so a slow or starved machine can only delay events and never turn
     * this red; that is why C11 needs no FX liveness void rule.
     */
    @Test
    fun `C11 a source that is not media is refused by probe or never reports READY and an error report is allowed`() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val sentence: ByteArray = "this is not a video, it is a sentence".toByteArray(Charsets.UTF_8)
        val clipFile: File = TestClip.copyToTempFile()
        val files: List<Pair<String, ByteArray>> = try {
            listOf(
                "zero-length.mp4" to ByteArray(0),
                "text.mp4" to sentence,
                "pattern-4k.mp4" to ByteArray(4096) { i: Int -> ((i * 31 + 7) and 0xFF).toByte() },
                "truncated.mp4" to clipFile.readBytes().copyOf(TRUNCATED_BYTES),
                "text.m4a" to sentence,
                "text.wav" to sentence,
                "text.txt" to sentence,
            )
        } finally {
            clipFile.delete()
        }

        val dir: File = Files.createTempDirectory("kmptoolkit-c11").toFile()
        val problems: MutableList<String> = mutableListOf()
        val refused: MutableList<Pair<String, Throwable>> = mutableListOf()
        val hostile: MutableList<Hostile> = mutableListOf()
        try {
            for ((name: String, bytes: ByteArray) in files) {
                val file = File(dir, name)
                file.writeBytes(bytes)
                val uri: String = file.toURI().toString()
                val source: NativeSource = try {
                    JavaFxBackend.probe(uri)
                } catch (t: Throwable) {
                    refused += name to t
                    continue
                }
                // Not in `rigs`: quiesce() calls pause() on every tracked player, which production never does to a never-ready one.
                val (rig: Rig, _) = build(source, tracked = false) { }
                hostile += Hostile(name, rig)
            }

            if (hostile.isEmpty()) {
                problems += "A1: no non-media source got past probe on this host, so C11's silent branch was not exercised: a finding for the advisor, not a flake"
            }
            val txt: Throwable? = refused.firstOrNull { it.first == "text.txt" }?.second
            if (txt == null) {
                problems += "A2: probe returned a source for text.txt: an unknown container must be refused at probe (05-platform-notes)"
            } else if (txt !is Exception) {
                problems += "A2: probe refused text.txt with ${txt::class.java.name}, which is not an Exception"
            }

            if (hostile.isNotEmpty()) {
                // The control is created after every hostile player, so each of them has been alive at least as long as it took.
                val (control: Rig, _) = create(clipUri()) { }
                control.events.readyWatch.await(READY_BOUND_MS) { "control clip not READY within $READY_BOUND_MS ms: no C11 verdict" }

                val futures: Array<CompletableFuture<ReadyReading>> = hostile.map { it.rig.events.readyWatch.future }.toTypedArray()
                try {
                    CompletableFuture.anyOf(*futures).get(NO_READY_WINDOW_MS, TimeUnit.MILLISECONDS)
                    val ready: List<String> = hostile.filter { it.rig.events.readyWatch.future.isDone }.map { it.name }
                    fail("onReady fired for a source that is not media: $ready")
                } catch (_: TimeoutException) {
                    // none, as the contract says
                }
                repeat(DRAIN_ROUND_TRIPS) { fx { } }

                val allowed: Set<NativeStatus> = setOf(NativeStatus.UNKNOWN, NativeStatus.HALTED)
                for (h: Hostile in hostile) {
                    val events: Recorder = h.rig.events
                    if (events.readyWatch.future.isDone) problems += "A3: ${h.name}: onReady fired for a source that is not media"
                    val now: NativeStatus = fx { h.rig.native.status }
                    if (now !in allowed) problems += "A4: ${h.name}: status $now implies a loaded source"
                    val seen: List<NativeStatus> = events.statuses.toList()
                    if (seen.any { it !in allowed }) problems += "A4: ${h.name}: onStatus reported $seen"
                    if (events.offFx.isNotEmpty()) problems += "A5: ${h.name}: callbacks off the FX thread: ${events.offFx}"
                }
            }
        } finally {
            // The sequence FxPlayback.open runs on a half-built playback after LoadTimedOut (the order of C9).
            for (h: Hostile in hostile) {
                h.rig.disposed = true
                try {
                    fx {
                        h.rig.native.stopPulse()
                        h.rig.native.detachView()
                        h.rig.native.clearEndOfMedia()
                        h.rig.native.cycleCount = 1
                        h.rig.native.dispose()
                    }
                } catch (t: Throwable) {
                    problems += "A6: ${h.name}: the dispose sequence threw $t"
                }
            }
            dir.deleteRecursively()
        }
        println("C11 on this host: refused by probe ${refused.map { "${it.first} (${it.second::class.java.simpleName})" }}, created and silent ${hostile.map { it.name }}")
        assertTrue(problems.isEmpty(), "C11: ${problems.joinToString("; ")}")
    }

    // ---- C12 -----------------------------------------------------------------------------------------------------

    /**
     * C12 (fact f1 of D2 r14 ruling 4, `reviews/d2-r14-ruling4-opus.md`; macOS only, skipped elsewhere): a plain `play()`
     * with the rate property at 2.0 starts the decoder at normal speed whatever the property holds, and the property then
     * forwards nothing for a write of 2.0 (C1), so only a write of another value and then 2.0 (the away-and-back that
     * `FxPlayback` gives a held speed) brings the decoder to 2x. `FxPlayback.applyDeferredRate` relies on both halves: a speed
     * held equal to the property is given away and back because a plain write of it would leave the decoder at normal speed.
     * The slope of the playhead, from the `currentTime` events as the FX thread publishes them (producer-side stamps), is
     * measured over [SLOPE_WINDOW_MS]: the first window after the play (from its first event) must stay below [UNITY_SLOPE_MAX]; the window that opens
     * [AFTER_CHANGE_SKIP_MS] after the away-and-back (the native player holds its position for a moment after a rate change
     * and then recovers, D-156) must be above [AT_RATE_SLOPE_MIN]. The away-and-back comes at least 400 ms after the play, past
     * the 200 ms the engine itself waits (H0 NUDGE: no freeze).
     */
    @Test
    fun `C12 a plain play at a rate of 2 starts at normal speed and a change to 1 and back to 2 brings the decoder to 2x`() {
        Assume.assumeTrue("C12 states a macOS decoder fact", System.getProperty("os.name").startsWith("Mac"))
        val rig: Rig = openReady()
        fx { rig.native.rate = 2.0 }
        // The idle gap of a rate written while paused: a play() inside it can freeze the decoder (W-IDLE), so the play waits it out.
        gap(QUIET_GAP_MS)
        val plain: Double = slopeAfter(rig, skipMs = 0L) { rig.native.play() }
        assertTrue(plain < UNITY_SLOPE_MAX, "a plain play() with the rate property at 2.0 moved the playhead $plain ms per ms: the decoder did not start at normal speed (f1)")
        assertEquals(2.0, fx { rig.native.rate }, "the property still holds 2.0 after the plain play()")

        val away: Double = slopeAfter(rig, skipMs = AFTER_CHANGE_SKIP_MS) {
            rig.native.rate = 1.0
            rig.native.rate = 2.0
        }
        println("C12 on this host: slope after a plain play at 2.0 = $plain, slope after the away-and-back = $away")
        assertTrue(away > AT_RATE_SLOPE_MIN, "after 1.0 and then 2.0 the playhead moved $away ms per ms: the away-and-back did not bring the decoder to 2x")
    }

    /**
     * The speed of the playhead in ms of media per ms of wall time: [stimulus] runs on the FX thread, and the window opens at
     * the first `currentTime` event [skipMs] or more after it and ends at the first event [SLOPE_WINDOW_MS] or more after the
     * opening one. Stamps are the producer-side ones of the events themselves; the first watch is registered in the task of
     * the stimulus, so no event can slip between them.
     */
    private fun slopeAfter(rig: Rig, skipMs: Long, stimulus: () -> Unit): Double {
        val opening: Watch<TimeSample> = fx {
            val bound: Long = producerNanos() + skipMs * NANOS_PER_MS
            rig.events.watchTime { it.nanos >= bound }.also { stimulus() }
        }
        val first: TimeSample = opening.await(HANG_BOUND_MS) { "no currentTime event $skipMs ms after the stimulus; samples ${rig.events.samples.map { it.valueMs }}" }
        val closing: Watch<TimeSample> = rig.events.watchTime { it.nanos >= first.nanos + SLOPE_WINDOW_MS * NANOS_PER_MS }
        val last: TimeSample = rig.events.samples.firstOrNull { it.nanos >= first.nanos + SLOPE_WINDOW_MS * NANOS_PER_MS }
            ?: closing.await(HANG_BOUND_MS) { "no currentTime event $SLOPE_WINDOW_MS ms after the opening one; samples ${rig.events.samples.map { it.valueMs }}" }
        val elapsedMs: Double = (last.nanos - first.nanos) / NANOS_PER_MS.toDouble()
        assertTrue(elapsedMs > 0.0, "the window of the slope is empty: first ${first.nanos}, last ${last.nanos}")
        return (last.valueMs - first.valueMs) / elapsedMs
    }

    /** The only real-time pause of C12: a gap between two stimuli, the independent variable of the check, never a wait for a condition. */
    private fun gap(ms: Long) {
        // timing-hygiene-ignore: TH1 inject the stimulus gap outside the idle gap of a rate written while paused (the variable of C12)
        Thread.sleep(ms)
    }

    private fun sentinelArray(size: Int): IntArray = IntArray(size + TAIL).also { it.fill(SENTINEL) }

    private fun tailIsIntact(pixels: IntArray, size: Int): Boolean = (size until size + TAIL).all { pixels[it] == SENTINEL }

    // ---- harness -------------------------------------------------------------------------------------------------

    private fun clipUri(): String {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        return TestClip.copyToTempFile().toURI().toString()
    }

    /**
     * Probes [uri] on the calling thread (the port says `probe` may block) and builds the player on the FX thread,
     * installing the events in that same task; [atCreate] runs in it too, before any callback can be delivered.
     */
    private fun <T> create(uri: String, atCreate: (JavaFxNativePlayer) -> T): Pair<Rig, T> =
        build(JavaFxBackend.probe(uri), tracked = true, atCreate)

    /** [create] after the probe; a player that is not [tracked] is not touched by [quiesce]. */
    private fun <T> build(source: NativeSource, tracked: Boolean, atCreate: (JavaFxNativePlayer) -> T): Pair<Rig, T> {
        val recorder = Recorder()
        val (native: JavaFxNativePlayer, extra: T) = fx {
            val created: JavaFxNativePlayer = JavaFxBackend.create(source) as JavaFxNativePlayer
            recorder.player = created
            created.setEvents(recorder)
            created to atCreate(created)
        }
        val rig = Rig(native, recorder)
        if (tracked) rigs += rig
        return rig to extra
    }

    /** A player of the checked-in clip, waited until READY. */
    private fun openReady(): Rig {
        val (rig: Rig, _) = create(clipUri()) { }
        rig.events.readyWatch.await(READY_BOUND_MS) { "the clip was not READY; status ${fx { rig.native.status }}" }
        return rig
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

    private companion object {
        /** A producer-side stamp: always taken in the FX callback that makes the sample, or by the liveness probe. */
        // timing-hygiene-ignore: TH2 producer the stamp is taken where the sample or the probe task is produced, never read after a sleep
        fun producerNanos(): Long = System.nanoTime()

        const val INDEFINITE: Int = -1
        const val SENTINEL: Int = 0x7F123456
        const val TAIL: Int = 16

        const val READY_BOUND_MS: Long = 15_000L
        const val HANG_BOUND_MS: Long = 15_000L
        const val STATUS_BOUND_MS: Long = 2_000L
        const val SEEK_BOUND_MS: Long = 3_000L
        const val END_BOUND_MS: Long = 15_000L
        const val LOOP_BOUND_MS: Long = 25_000L
        const val NO_SECOND_END_MS: Long = 1_500L
        const val PICTURE_BOUND_MS: Long = 10_000L
        const val NO_READY_WINDOW_MS: Long = 3_000L
        const val TRUNCATED_BYTES: Int = 300

        const val KEY_FRAME_INTERVAL_MS: Double = 1_000.0
        const val SEEK_TARGET_MS: Double = 1_540.0
        const val NEAR_START_TARGET_MS: Double = 80.0
        const val DURATION_TOLERANCE_MS: Double = 100.0

        const val WINDOW_END_MS: Double = 2_000.0
        const val MAX_EVENT_GAP_MS: Double = 1_000.0
        const val MIN_EVENTS_IN_WINDOW: Int = 3
        const val MAX_WINDOWS: Int = 3
        const val PROBE_PERIOD_MS: Long = 50L
        const val STARVED_LATENCY_MS: Double = 400.0
        const val DRAIN_ROUND_TRIPS: Int = 3

        const val NANOS_PER_MS: Long = 1_000_000L
        const val QUIET_GAP_MS: Long = 400L
        const val SLOPE_WINDOW_MS: Long = 400L
        const val AFTER_CHANGE_SKIP_MS: Long = 300L
        const val UNITY_SLOPE_MAX: Double = 1.3
        const val AT_RATE_SLOPE_MIN: Double = 1.6
    }
}
