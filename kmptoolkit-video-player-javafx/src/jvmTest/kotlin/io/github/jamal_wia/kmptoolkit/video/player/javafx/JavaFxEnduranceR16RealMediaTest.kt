package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.lang.management.ManagementFactory
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * D2 round 16 (breaker jr, character: ENDURANCE AND PLATFORM BREADTH): long real-media runs on the real JavaFX decoder.
 *
 * OPT-IN TIER. The class skips unless `-Pkmptoolkit.test.endurance=true` (forwarded to the test JVM like every `kmptoolkit.test.*`
 * knob), so the default gate never plays it; it needs the exclusive real-media slot like every real-media class. The lengths are
 * knobs, each a `-Pkmptoolkit.test.endurance.<name>=<n>` property, and each test fits one Gradle command under 10 minutes at its
 * default; run the tests one per command with `--tests '...JavaFxEnduranceR16RealMediaTest.<method>'`:
 *
 * - `fuzzSeconds` (90) and `seeds` (comma list, default `16001,16002,16003`): the seeded random session, `aSeededRandomSessionKeepsEveryDocumentedSentence`;
 * - `pauseCycles` (1000): the pause/start endurance at 2x, `aThousandPauseAndStartCyclesAtTwiceTheSpeedNeverFreezeOrDrift`;
 * - `loopSeconds` (90 per speed, so 3 minutes for 2x and 4x): `loopingAtTwoAndFourTimesReturnsToTheAskedSpeedOnEveryWrap`;
 * - `releaseCycles` (300): `threeHundredPrepareAndReleaseCyclesOfTheAudioClipLeaveNoThreadDescriptorOrHeapBehind`.
 *
 * Every random choice comes from `Random(seed)` and the seed is in every failure message; every wait is a flow wait or a latch with a hang
 * bound; the only sleep is the gap between two calls of the random session, which is the stimulus, not a wait for the code under test.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxEnduranceR16RealMediaTest {

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "the endurance tier is opt-in: run with -Pkmptoolkit.test.endurance=true",
            System.getProperty("kmptoolkit.test.endurance").toBoolean(),
        )
        assumeJavaFxMedia()
    }

    private fun knob(name: String, default: Long): Long =
        System.getProperty("kmptoolkit.test.endurance.$name")?.toLongOrNull() ?: default

    private fun config(): VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = 25L, maxPlaybackSpeed = 4f)

    private fun prepare(player: VideoPlayer, asset: String = TestClip.ASSET) = runBlocking {
        withTimeout(HANG_MS) { player.prepare(VideoSource.Asset(asset)) }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
    }

    // ---- Q1: a seeded random session with an oracle and a monitor ----

    /**
     * Watches the flows of one player from the arrival side: the stamps are taken where the value arrives, never read after a sleep. It
     * records a violation, with the context, instead of throwing in a collector.
     */
    private class SessionMonitor(private val player: VideoPlayer, private val seed: Long, scope: CoroutineScope) {
        val violations: MutableList<String> = CopyOnWriteArrayList()
        val trace: MutableList<String> = CopyOnWriteArrayList()

        @Volatile var lastOpNanos: Long = nanos()
        @Volatile var lastPositionChangeNanos: Long = nanos()
        @Volatile var stateSinceNanos: Long = nanos()
        @Volatile var looping: Boolean = false
        @Volatile private var lastPosition: Long = 0L
        @Volatile private var stateName: String = "Idle"
        val wraps: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

        private fun nanos(): Long {
            // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives or the call is made
            return System.nanoTime()
        }

        private val startNanos: Long = nanos()

        fun op(text: String) {
            lastOpNanos = nanos()
            trace += "+${(lastOpNanos - startNanos) / NANOS_PER_MILLI} ms $text"
        }

        fun context(): String = "seed $seed, state $stateName, looping $looping, last ops ${trace.takeLast(14)}"

        private fun violation(text: String) {
            if (violations.size < 5) violations += "$text; ${context()}"
        }

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect { state: VideoPlayerState ->
                    val name: String = state::class.simpleName ?: "?"
                    if (name != stateName) {
                        stateName = name
                        stateSinceNanos = nanos()
                    }
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.playbackPositionFlow.collect { now: Long ->
                    val at: Long = nanos()
                    val prev: Long = lastPosition
                    val sinceOpMs: Long = (at - lastOpNanos) / NANOS_PER_MILLI
                    if (now < prev - BACK_TOLERANCE_MS) {
                        when {
                            sinceOpMs < OP_SHADOW_MS -> Unit // a seek or a replay moves it on purpose
                            looping && prev - now > WRAP_DROP_MS -> wraps.incrementAndGet()
                            else -> violation("the position jumped back from $prev to $now with no call for $sinceOpMs ms")
                        }
                    } else if (now > prev && stateName != "Playing" && (at - stateSinceNanos) / NANOS_PER_MILLI > QUIET_MS && sinceOpMs > QUIET_MS) {
                        violation("the position advanced from $prev to $now while the state was $stateName for ${(at - stateSinceNanos) / NANOS_PER_MILLI} ms")
                    }
                    if (now != prev) lastPositionChangeNanos = at
                    lastPosition = now
                }
            }
        }

        /** A Playing player whose playhead has not changed, and which nobody called, for [FREEZE_BOUND_MS]. */
        fun checkFreeze() {
            if (stateName != "Playing") return
            val latest: Long = maxOf(lastPositionChangeNanos, lastOpNanos, stateSinceNanos)
            val stillMs: Long = (nanos() - latest) / NANOS_PER_MILLI
            if (stillMs > FREEZE_BOUND_MS) violation("the playhead stood still for $stillMs ms while Playing")
        }
    }

    private fun runSession(seed: Long, seconds: Long) {
        val rng = Random(seed)
        val player: VideoPlayer = createJavaFxVideoPlayer(config())
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val monitor = SessionMonitor(player, seed, scope)
        try {
            prepare(player)
            var asked: Float = 1f
            val range: ClosedFloatingPointRange<Float> = player.playbackSpeedRange
            val speeds: List<Float> = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)
            val gaps: List<Long> = listOf(0L, 0L, 20L, 100L, 300L, 600L, 1_200L)
            // timing-hygiene-ignore: TH2 bound the session length is real time
            val deadline: Long = System.nanoTime() + seconds * 1_000_000_000L
            // timing-hygiene-ignore: TH2 bound the session length is real time
            while (System.nanoTime() < deadline && monitor.violations.isEmpty()) {
                val before: VideoPlayerState = player.stateFlow.value
                val roll: Int = rng.nextInt(100)
                when {
                    roll < 15 -> {
                        val speed: Float = speeds[rng.nextInt(speeds.size)]
                        monitor.op("speed $speed")
                        player.setPlaybackSpeed(speed)
                        asked = speed.coerceIn(range.start, range.endInclusive)
                    }
                    roll < 25 -> {
                        val target: Long = rng.nextLong(0L, TestClip.DURATION_MS + 1L)
                        monitor.op("seekTo $target")
                        player.seekTo(target)
                        val after: VideoPlayerState = player.stateFlow.value
                        if (before is VideoPlayerState.Paused) {
                            assertTrue(after is VideoPlayerState.Paused, "seek while Paused left $after; ${monitor.context()}")
                        }
                    }
                    roll < 40 -> {
                        monitor.op("play")
                        player.play()
                        val after: VideoPlayerState = player.stateFlow.value
                        if (before is VideoPlayerState.Ready || before is VideoPlayerState.Paused || before is VideoPlayerState.Completed) {
                            assertTrue(after is VideoPlayerState.Playing || after is VideoPlayerState.Completed, "play() from $before left $after; ${monitor.context()}")
                        }
                    }
                    roll < 55 -> {
                        monitor.op("pause")
                        player.pause()
                        val after: VideoPlayerState = player.stateFlow.value
                        if (before is VideoPlayerState.Playing) {
                            assertTrue(after is VideoPlayerState.Paused || after is VideoPlayerState.Completed, "pause() from Playing left $after; ${monitor.context()}")
                        }
                    }
                    roll < 63 -> {
                        val mode: RepeatMode = RepeatMode.entries[rng.nextInt(RepeatMode.entries.size)]
                        monitor.op("repeat $mode")
                        player.setRepeatMode(mode)
                        monitor.looping = mode == RepeatMode.One
                    }
                    roll < 68 -> {
                        monitor.op("stop")
                        player.stop()
                        val after: VideoPlayerState = player.stateFlow.value
                        if (before !is VideoPlayerState.Idle) {
                            assertTrue(after is VideoPlayerState.Ready, "stop() from $before left $after; ${monitor.context()}")
                        }
                    }
                    roll < 75 -> {
                        monitor.op("replay")
                        player.replay()
                        val after: VideoPlayerState = player.stateFlow.value
                        assertTrue(after is VideoPlayerState.Playing || after is VideoPlayerState.Completed, "replay() left $after; ${monitor.context()}")
                    }
                    roll < 82 -> {
                        monitor.op("volume")
                        player.setVolume(rng.nextFloat() * 1.4f - 0.2f)
                        assertTrue(player.volumeFlow.value in 0f..1f, "volume ${player.volumeFlow.value}; ${monitor.context()}")
                    }
                    roll < 87 -> {
                        val muted: Boolean = rng.nextBoolean()
                        monitor.op("mute $muted")
                        player.setMuted(muted)
                        assertEquals(muted, player.isMutedFlow.value, "mute; ${monitor.context()}")
                    }
                    else -> {
                        val amount: Long = if (rng.nextBoolean()) 1_000L else -1_000L
                        monitor.op("seekForward $amount")
                        player.seekForward(amount)
                    }
                }
                assertEquals(asked, player.playbackSpeedFlow.value, "the speed flow is not the asked speed; ${monitor.context()}")
                assertTrue(player.playbackPositionFlow.value >= 0L, "negative position; ${monitor.context()}")
                var left: Long = gaps[rng.nextInt(gaps.size)]
                while (left > 0L) {
                    val step: Long = minOf(left, GAP_STEP_MS)
                    // timing-hygiene-ignore: TH1 inject the gap between two consumer calls is the stimulus of the session, not a wait for the code under test
                    Thread.sleep(step)
                    monitor.checkFreeze()
                    left -= step
                }
                monitor.checkFreeze()
            }
            assertTrue(monitor.violations.isEmpty(), "the random session found: ${monitor.violations}")
            // The oracle of 'the last asked speed wins': a quiet phase on a looping clip; after the settle window the measured slope of the
            // best wrap-free stretch is the asked speed, and no stretch runs faster than it.
            val finalSpeed: Float = listOf(1.5f, 2f, 3f, 4f)[rng.nextInt(4)]
            monitor.op("final: repeat One, speed $finalSpeed, replay")
            player.setRepeatMode(RepeatMode.One)
            monitor.looping = true
            player.setPlaybackSpeed(finalSpeed)
            player.replay()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
            val samples: List<Pair<Long, Long>> = samplesOver(player, FINAL_WINDOW_MS)
            val best: Double = bestSlope(samples, 400L)
            val fastest: Double = bestSlope(samples, 1_000L)
            assertTrue(monitor.violations.isEmpty(), "the final phase found: ${monitor.violations}")
            assertTrue(best >= 0.7 * finalSpeed, "seed $seed: the best 400 ms slope after the last ask of $finalSpeed was $best; ${monitor.context()}")
            assertTrue(fastest <= 1.35 * finalSpeed, "seed $seed: a 1 s window ran at $fastest, faster than the asked $finalSpeed; ${monitor.context()}")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun aSeededRandomSessionKeepsEveryDocumentedSentence() {
        val seeds: List<Long> = (System.getProperty("kmptoolkit.test.endurance.seeds") ?: "16001,16002,16003")
            .split(',').mapNotNull { it.trim().toLongOrNull() }
        val seconds: Long = knob("fuzzSeconds", 90L)
        for (seed: Long in seeds) runSession(seed, seconds)
    }

    // ---- shared measuring ----

    /**
     * The best slope (media ms per wall ms) over any wrap-free run of samples that spans at least [minWallMs] of wall time, or 0 when no run
     * does: the playhead as (nanoTime, position) pairs taken where the flow value arrives. A drop of more than 500 ms (a wrap or a seek back)
     * ends a run. A window rather than a whole stretch, because the stretch after a wrap includes the hold of the reported time at the start of the
     * cycle (05-platform-notes: the second cycle of a loop holds still for 0.3 s to 1.2 s), which says nothing about the speed.
     */
    private fun bestSlope(points: List<Pair<Long, Long>>, minWallMs: Long): Double {
        var best = 0.0
        var runStart = 0
        for (index: Int in 1..points.size) {
            if (index < points.size && points[index].second >= points[index - 1].second - 500L) continue
            var j = runStart
            for (i: Int in runStart until index) {
                if (j < i) j = i
                while (j < index && (points[j].first - points[i].first) / 1_000_000.0 < minWallMs) j++
                if (j >= index) break
                val wallMs: Double = (points[j].first - points[i].first) / 1_000_000.0
                best = maxOf(best, (points[j].second - points[i].second) / wallMs)
            }
            runStart = index
        }
        return best
    }

    /** The samples of the position flow for [windowMs] of flow time, each stamped where the value arrives. */
    private fun samplesOver(player: VideoPlayer, windowMs: Long): List<Pair<Long, Long>> {
        val samples: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val done = CountDownLatch(1)
        // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives
        val start: Long = System.nanoTime()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.playbackPositionFlow.collect { position: Long ->
                // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives
                val now: Long = System.nanoTime()
                samples += now to position
                if (now - start > windowMs * NANOS_PER_MILLI) done.countDown()
            }
        }
        // timing-hygiene-ignore: TH2 bound the hang bound of a window that needs the position flow to keep delivering
        assertTrue(done.await(HANG_MS, TimeUnit.MILLISECONDS), "the position flow stopped for $HANG_MS ms")
        scope.cancel()
        return samples.toList()
    }

    // ---- Q2a: a thousand pause/start cycles at 2x ----

    private val sessionField: Field = JavaFxVideoEngine::class.java.getDeclaredField("session").apply { isAccessible = true }

    private class FxSnapshot(
        val holders: List<String>,
        val playGeneration: Long,
        val delayedPlayPending: Boolean,
        val idleStamped: Boolean,
        val deferredAfterWrap: Boolean,
        val nativeRate: String,
        val nativeStatus: String,
        val playIssued: Boolean,
    ) {
        override fun toString(): String =
            "holders=$holders generation=$playGeneration delayedPlay=$delayedPlayPending idleStamp=$idleStamped afterWrap=$deferredAfterWrap " +
                "nativeRate=$nativeRate nativeStatus=$nativeStatus playIssued=$playIssued"
    }

    /** The private holders of the engine's playback, read ON the FX thread, where every engine task runs, so no task is half way through. */
    private fun fxSnapshot(engine: JavaFxVideoEngine): FxSnapshot? {
        val session: Any = sessionField.get(engine) ?: return null
        val playbackField: Field = session.javaClass.getDeclaredField("playback").apply { isAccessible = true }
        val playback: FxPlayback = playbackField.get(session) as FxPlayback? ?: return null
        fun field(name: String): Field = FxPlayback::class.java.getDeclaredField(name).apply { isAccessible = true }
        var result: FxSnapshot? = null
        val latch = CountDownLatch(1)
        Platform.runLater {
            try {
                result = FxSnapshot(
                    holders = listOf("pendingNudge", "settleNudge", "deferredRate").mapNotNull { name: String -> field(name).get(playback)?.let { "$name=$it" } },
                    playGeneration = field("playGeneration").getLong(playback),
                    delayedPlayPending = field("delayedPlayPending").getBoolean(playback),
                    idleStamped = field("idleRateWriteNanos").get(playback) != null,
                    deferredAfterWrap = field("deferredAfterWrap").getBoolean(playback),
                    nativeRate = field("native").get(playback).let { nativePlayer: Any -> nativePlayer.javaClass.methods.firstOrNull { it.name == "getRate" }?.invoke(nativePlayer).toString() },
                    nativeStatus = field("native").get(playback).let { nativePlayer: Any -> nativePlayer.javaClass.methods.firstOrNull { it.name == "getStatus" }?.invoke(nativePlayer).toString() },
                    playIssued = field("playIssued").getBoolean(playback),
                )
            } finally {
                latch.countDown()
            }
        }
        // timing-hygiene-ignore: TH2 bound the hang bound of one task on the FX thread
        assertTrue(latch.await(HANG_MS, TimeUnit.MILLISECONDS), "the FX thread did not run a task for $HANG_MS ms")
        return result
    }

    private class Resources(val threads: Int, val nonDaemon: Int, val descriptors: Long?, val heapBytes: Long) {
        override fun toString(): String = "threads=$threads nonDaemon=$nonDaemon fds=$descriptors heap=${heapBytes / MEGA} MB"
    }

    /** The JVM's own counters, after the collector ran: nothing here needs an attach tool or `lsof`. */
    private fun resources(): Resources {
        repeat(3) { System.gc() }
        val threads = ManagementFactory.getThreadMXBean()
        val os = ManagementFactory.getOperatingSystemMXBean()
        // path-hygiene-ignore: PH2 the descriptor count is read through the JVM's own MXBean and the result is null where the MXBean is not a Unix one
        val descriptors: Long? = (os as? com.sun.management.UnixOperatingSystemMXBean)?.openFileDescriptorCount
        val live: Int = threads.threadCount
        val daemon: Int = threads.daemonThreadCount
        return Resources(live, live - daemon, descriptors, ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
    }

    @Test
    fun aThousandPauseAndStartCyclesAtTwiceTheSpeedNeverFreezeOrDrift() {
        val cycles: Long = knob("pauseCycles", 1_000L)
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        val player: VideoPlayer = createVideoPlayer(engine, config(), Dispatchers.Default)
        try {
            prepare(player)
            player.setRepeatMode(RepeatMode.One)
            player.setPlaybackSpeed(2f)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
            var baseline: Resources? = null
            var firstGeneration = 0L
            for (cycle: Long in 1..cycles) {
                player.pause()
                assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "cycle $cycle: after pause() the state is ${player.stateFlow.value}")
                val before: Long = player.playbackPositionFlow.value
                player.play()
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "cycle $cycle: after play() the state is ${player.stateFlow.value}")
                try {
                    runBlocking { withTimeout(FREEZE_WAIT_MS) { player.playbackPositionFlow.first { abs(it - before) >= 60L } } }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    fail("cycle $cycle of $cycles: the playhead did not move for $FREEZE_WAIT_MS ms after a resume at 2x (from $before, now ${player.playbackPositionFlow.value}, state ${player.stateFlow.value})")
                }
                assertEquals(2f, player.playbackSpeedFlow.value, "cycle $cycle: the speed flow")
                if (cycle % CHECK_EVERY == 0L) {
                    val samples: List<Pair<Long, Long>> = samplesOver(player, FINAL_WINDOW_MS)
                    val best: Double = bestSlope(samples, 500L)
                    val fastest: Double = bestSlope(samples, 1_000L)
                    assertTrue(best >= 1.6, "cycle $cycle of $cycles: the best 500 ms slope after a resume at 2x was $best; ${fxSnapshot(engine)}")
                    assertTrue(fastest <= 2.7, "cycle $cycle of $cycles: a 1 s window ran at $fastest, faster than 2x")
                    val snapshot: FxSnapshot = checkNotNull(fxSnapshot(engine)) { "no playback at cycle $cycle" }
                    assertTrue(snapshot.holders.size <= 1, "cycle $cycle: I1, two speed holders are set: $snapshot")
                    val now: Resources = resources()
                    if (cycle == CHECK_EVERY) {
                        baseline = now
                        firstGeneration = snapshot.playGeneration
                    }
                    println("endurance pause cycle $cycle: $snapshot; $now (generation growth ${snapshot.playGeneration - firstGeneration})")
                    val base: Resources = checkNotNull(baseline)
                    assertTrue(now.threads - base.threads <= THREAD_GROWTH, "cycle $cycle: threads grew from ${base.threads} to ${now.threads}")
                    assertTrue(now.heapBytes - base.heapBytes <= HEAP_GROWTH_MB * MEGA, "cycle $cycle: the heap after a collection grew from $base to $now")
                }
            }
            player.pause()
            val rest: FxSnapshot = checkNotNull(fxSnapshot(engine))
            assertTrue(rest.holders.size <= 1, "after $cycles cycles: I1 $rest")
        } finally {
            player.release()
        }
    }

    // ---- Q2b: minutes of looping at 2x and 4x ----

    @Test
    fun loopingAtTwoAndFourTimesReturnsToTheAskedSpeedOnEveryWrap() {
        val seconds: Long = knob("loopSeconds", 90L)
        for (speed: Float in listOf(2f, 4f)) {
            val player: VideoPlayer = createJavaFxVideoPlayer(config())
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val samples: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
            val states: MutableList<String> = CopyOnWriteArrayList()
            val done = CountDownLatch(1)
            try {
                prepare(player)
                player.setRepeatMode(RepeatMode.One)
                player.setPlaybackSpeed(speed)
                // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives
                val start: Long = System.nanoTime()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    player.stateFlow.collect { state: VideoPlayerState -> states += state::class.simpleName.orEmpty() }
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    player.playbackPositionFlow.collect { position: Long ->
                        // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives
                        val now: Long = System.nanoTime()
                        samples += now to position
                        if (now - start > seconds * 1_000_000_000L) done.countDown()
                    }
                }
                player.play()
                // timing-hygiene-ignore: TH2 bound the loop runs for its real length, the bound is that length plus the hang bound
                assertTrue(done.await(seconds * 1_000L + HANG_MS, TimeUnit.MILLISECONDS), "at ${speed}x the position flow stopped (${samples.size} samples)")
                val points: List<Pair<Long, Long>> = samples.toList()
                // cut at the wraps
                val cycles: MutableList<List<Pair<Long, Long>>> = mutableListOf()
                var from = 0
                for (index: Int in 1 until points.size) {
                    if (points[index].second < points[index - 1].second - WRAP_DROP_MS) {
                        cycles += points.subList(from, index)
                        from = index
                    }
                }
                // the first cycle (the start-up) and the last one (cut by the window) are not whole
                val whole: List<List<Pair<Long, Long>>> = if (cycles.size > 2) cycles.drop(1) else emptyList()
                assertTrue(cycles.size >= seconds / 6, "at ${speed}x only ${cycles.size} wraps in $seconds s")
                var longestStill = 0L
                var late = 0
                var lateOk = 0
                val slow: MutableList<String> = mutableListOf()
                for (cycle: List<Pair<Long, Long>> in whole) {
                    for (i: Int in 1 until cycle.size) {
                        if (cycle[i].second != cycle[i - 1].second) continue
                    }
                    var lastChange: Long = cycle.first().first
                    var lastValue: Long = cycle.first().second
                    for ((at: Long, value: Long) in cycle) {
                        if (value != lastValue) {
                            longestStill = maxOf(longestStill, (at - lastChange) / NANOS_PER_MILLI)
                            lastChange = at
                            lastValue = value
                        }
                    }
                    val tail: List<Pair<Long, Long>> = cycle.filter { it.second >= LATE_FROM_MS }
                    if (tail.size >= 2) {
                        val wallMs: Double = (tail.last().first - tail.first().first) / 1_000_000.0
                        if (wallMs >= 100.0) {
                            late++
                            val slope: Double = (tail.last().second - tail.first().second) / wallMs
                            if (slope >= 0.75 * speed) lateOk++ else slow += "${"%.2f".format(slope)}"
                        }
                    }
                }
                println("endurance loop ${speed}x: ${cycles.size} wraps in $seconds s, longest still ${longestStill} ms, late slope ok $lateOk of $late (slow: ${slow.take(10)}), states ${states.distinct()}")
                assertTrue(states.none { it == "Completed" || it == "Error" }, "at ${speed}x the player left Playing: ${states.distinct()}")
                assertTrue(longestStill < FREEZE_BOUND_MS, "at ${speed}x the playhead stood still for $longestStill ms inside a cycle")
                assertTrue(late >= 3, "at ${speed}x only $late cycles had a measurable late stretch")
                assertTrue(lateOk >= late * 0.95, "at ${speed}x only $lateOk of $late cycles returned to the asked speed (slopes of the others: $slow)")
            } finally {
                scope.cancel()
                player.release()
            }
        }
    }

    // ---- Q2c: prepare and release of the audio-only clip ----

    @Test
    fun threeHundredPrepareAndReleaseCyclesOfTheAudioClipLeaveNoThreadDescriptorOrHeapBehind() {
        val cycles: Long = knob("releaseCycles", 300L)
        fun one() {
            val player: VideoPlayer = createJavaFxVideoPlayer(config())
            try {
                prepare(player, TestClip.AUDIO_ASSET)
                player.play()
                runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 100L } } }
            } finally {
                player.release()
            }
        }
        repeat(10) { one() } // warm-up: the toolkit's and the media stack's one-off threads, files and classes
        val base: Resources = resources()
        val samples: MutableList<Pair<Long, Resources>> = mutableListOf()
        for (cycle: Long in 1..cycles) {
            one()
            if (cycle % (cycles / 3L).coerceAtLeast(1L) == 0L) samples += cycle to resources()
        }
        // JavaFX Media starts a daemon timer thread per player that played and the JDK reaps it when the timer is collected
        var end: Resources = resources()
        // timing-hygiene-ignore: TH2 bound the collector gets a bounded time to reap the media stack's timers
        val gcDeadline: Long = System.nanoTime() + 20_000_000_000L
        // timing-hygiene-ignore: TH2 bound the collector gets a bounded time to reap the media stack's timers
        while (end.threads - base.threads > THREAD_GROWTH && System.nanoTime() < gcDeadline) end = resources()
        println("endurance release: base $base; samples ${samples.map { (c, r) -> "$c: $r" }}; end $end")
        assertTrue(end.threads - base.threads <= THREAD_GROWTH, "threads grew from ${base.threads} to ${end.threads} over $cycles cycles")
        assertTrue(end.nonDaemon <= base.nonDaemon, "non-daemon threads grew from ${base.nonDaemon} to ${end.nonDaemon} over $cycles cycles")
        val baseFds: Long? = base.descriptors
        val endFds: Long? = end.descriptors
        if (baseFds != null && endFds != null) {
            assertTrue(endFds - baseFds <= FD_GROWTH, "open descriptors grew from $baseFds to $endFds over $cycles cycles")
        }
        assertTrue(end.heapBytes - base.heapBytes <= HEAP_GROWTH_MB * MEGA, "the heap after a collection grew from $base to $end over $cycles cycles")
        // the FX thread is not clogged with tasks the released players left behind
        val sentinel = CountDownLatch(1)
        Platform.runLater { sentinel.countDown() }
        // timing-hygiene-ignore: TH2 bound the hang bound of one task on the FX thread
        assertTrue(sentinel.await(HANG_MS, TimeUnit.MILLISECONDS), "a task posted after $cycles released players did not run within $HANG_MS ms")
    }

    private companion object {
        const val HANG_MS: Long = 30_000L
        const val FREEZE_WAIT_MS: Long = 8_000L
        const val FREEZE_BOUND_MS: Long = 4_000L
        const val FINAL_WINDOW_MS: Long = 6_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val MEGA: Long = 1024L * 1024L
        const val BACK_TOLERANCE_MS: Long = 50L
        const val OP_SHADOW_MS: Long = 2_500L
        const val QUIET_MS: Long = 1_500L
        const val WRAP_DROP_MS: Long = 1_000L
        const val GAP_STEP_MS: Long = 25L
        val CHECK_EVERY: Long get() = System.getProperty("kmptoolkit.test.endurance.checkEvery")?.toLongOrNull() ?: 100L
        const val LATE_FROM_MS: Long = 1_800L
        const val THREAD_GROWTH: Int = 8
        const val FD_GROWTH: Long = 30L
        const val HEAP_GROWTH_MB: Long = 32L
    }
}
