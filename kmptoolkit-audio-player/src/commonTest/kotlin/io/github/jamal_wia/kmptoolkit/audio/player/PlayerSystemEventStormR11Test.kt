package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 11, Q2 for the player: seeded random sequences of transport calls interleaved with what the platform can
 * do to a player (the stream ends, playback fails, a late completion or failure after an unload or a release, and
 * the events the player is documented NOT to observe: audio focus loss and gain, a route change, an interruption),
 * in virtual time, against a model written from the transition tables of
 * `docs/kmptoolkit-audio-player/03-guide.md` and `04-api-reference.md`. Per step: the state (kind, duration and the
 * paused position), the position flow, the speed, the engine calls the player made, no state history edge the docs
 * do not allow, and after the last step a release that frees the engine and detaches the listener.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSystemEventStormR11Test {

    private enum class Kind { Idle, Ready, Playing, Paused, Completed, Error }

    private val sources: List<AudioSource> = listOf(
        AudioSource.Remote("https://example.test/a.mp3"),
        AudioSource.File("/music/b.mp3"),
        AudioSource.Asset("sounds/c.wav"),
    )

    private fun kindOf(state: PlayerState): Kind = when (state) {
        PlayerState.Idle -> Kind.Idle
        PlayerState.Preparing -> error("Preparing is not at rest here")
        is PlayerState.Ready -> Kind.Ready
        is PlayerState.Playing -> Kind.Playing
        is PlayerState.Paused -> Kind.Paused
        is PlayerState.Completed -> Kind.Completed
        is PlayerState.Error -> Kind.Error
    }

    private val allowed: Map<Kind, Set<Kind>> = mapOf(
        Kind.Idle to setOf(Kind.Ready, Kind.Error),
        Kind.Ready to setOf(Kind.Playing, Kind.Paused, Kind.Ready, Kind.Error, Kind.Idle),
        Kind.Playing to setOf(Kind.Paused, Kind.Completed, Kind.Ready, Kind.Error, Kind.Idle, Kind.Playing),
        Kind.Paused to setOf(Kind.Playing, Kind.Ready, Kind.Error, Kind.Idle, Kind.Paused),
        Kind.Completed to setOf(Kind.Playing, Kind.Paused, Kind.Ready, Kind.Error, Kind.Idle),
        Kind.Error to setOf(Kind.Ready, Kind.Error, Kind.Idle),
    )

    private fun run(seed: Int, steps: Int) = runTest {
        val random = Random(seed)
        val engine = FakePlaybackEngine(durationMs = 40_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        val history: MutableList<PlayerState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.stateFlow.collect { history += it } }
        runCurrent()
        val log: MutableList<String> = mutableListOf()
        var step = 0

        fun fail(message: String): Nothing = error("seed=$seed step=$step: $message\nlog: ${log.takeLast(14)}")

        // The model.
        var kind: Kind = Kind.Idle
        var duration: Long = 0L
        var position: Long = 0L
        var speed: Float = 1.0f
        var released = false
        var errorCause: Throwable? = null
        var releasedError: PlayerState.Error? = null
        val min: Float = AudioPlayerConfig().minPlaybackSpeed
        val max: Float = AudioPlayerConfig().maxPlaybackSpeed

        fun playable(): Boolean = kind in setOf(Kind.Ready, Kind.Playing, Kind.Paused, Kind.Completed)

        fun verify(after: String) {
            log += after
            val actual: PlayerState = player.stateFlow.value
            if (kindOf(actual) != kind) fail("after $after: state $actual, model $kind")
            when (actual) {
                is PlayerState.Ready -> if (actual.duration != duration) fail("Ready duration ${actual.duration} vs $duration")
                is PlayerState.Playing -> if (actual.duration != duration) fail("Playing duration ${actual.duration} vs $duration")
                is PlayerState.Paused -> {
                    if (actual.duration != duration) fail("Paused duration ${actual.duration} vs $duration")
                    if (actual.currentPosition != position) fail("Paused position ${actual.currentPosition} vs $position")
                }
                is PlayerState.Completed -> if (actual.duration != duration) fail("Completed duration ${actual.duration} vs $duration")
                is PlayerState.Error -> {
                    if (errorCause != null && actual.cause !== errorCause) fail("Error cause ${actual.cause} vs $errorCause")
                    if (released && actual !== releasedError) fail("a released player published a different Error object")
                }
                else -> Unit
            }
            val flow: Long = player.playbackPositionFlow.value
            if (flow < 0L || (duration > 0L && flow > duration)) fail("position flow $flow outside 0..$duration")
            // The flow carries the playhead in every state (04 says "0 otherwise", which is not so: report R11-02).
            if (kind == Kind.Idle && flow != 0L) fail("position flow $flow in Idle")
            if ((kind == Kind.Ready || kind == Kind.Paused) && flow != position) fail("position flow $flow in $kind, model playhead $position")
            if (player.playbackSpeed != speed) fail("speed ${player.playbackSpeed} vs $speed")
            if (engine.isPlaying != (kind == Kind.Playing)) fail("engine isPlaying=${engine.isPlaying} while $kind")
            if (released && (engine.hasListener)) fail("a listener is left on the engine after release")
            if (!released && !engine.hasListener) fail("the player lost its engine listener")
        }

        while (step < steps) {
            step++
            val pick: Int = random.nextInt(100)
            when {
                pick < 10 -> { // prepare
                    val failing: Boolean = random.nextInt(5) == 0
                    val source: AudioSource = sources[random.nextInt(sources.size)]
                    val cause = IllegalStateException("load $step")
                    engine.loadFailure = if (failing) cause else null
                    engine.durationMs = if (random.nextInt(6) == 0) 0L else 20_000L + random.nextInt(40_000)
                    val job = launch { player.prepare(source) }
                    runCurrent()
                    if (!job.isCompleted) fail("prepare did not finish")
                    engine.loadFailure = null
                    if (released) {
                        val state: PlayerState = player.stateFlow.value
                        if (releasedError == null) releasedError = assertIs<PlayerState.Error>(state)
                        errorCause = releasedError!!.cause
                        kind = Kind.Error
                    } else if (failing) {
                        kind = Kind.Error
                        errorCause = cause
                        position = 0L
                    } else {
                        kind = Kind.Ready
                        duration = engine.durationMs
                        position = 0L
                        errorCause = null
                    }
                    verify("prepare${if (failing) "(fails)" else ""}")
                }
                pick < 22 -> { // play
                    player.play()
                    if (!released && (kind == Kind.Ready || kind == Kind.Paused || kind == Kind.Completed)) {
                        if (kind == Kind.Completed || (duration > 0L && position >= duration)) position = 0L
                        kind = Kind.Playing
                    }
                    verify("play")
                }
                pick < 30 -> { // pause
                    player.pause()
                    if (!released && kind == Kind.Playing) {
                        position = engine.positionMs
                        kind = Kind.Paused
                    }
                    verify("pause")
                }
                pick < 36 -> { // stop
                    player.stop()
                    if (!released && playable()) {
                        kind = Kind.Ready
                        position = 0L
                    }
                    verify("stop")
                }
                pick < 46 -> { // seeks
                    val target: Long = when (random.nextInt(5)) {
                        0 -> Long.MAX_VALUE
                        1 -> 0L
                        2 -> duration
                        else -> random.nextLong(0, 60_000)
                    }
                    val base: Long = if (kind == Kind.Completed) duration else position
                    val amount: Long = target.coerceAtMost(30_000L)
                    val wanted: Long = when (random.nextInt(3)) {
                        0 -> { player.seekTo(target); target }
                        1 -> { player.seekForward(amount); base + amount }
                        else -> { player.seekBackward(amount); base - amount }
                    }
                    if (!released && playable()) {
                        val expected: Long = if (duration > 0L) wanted.coerceIn(0L, duration) else wanted.coerceAtLeast(0L)
                        if (engine.positionMs != expected) fail("seek to $wanted (base $base, length $duration): the engine was told ${engine.positionMs}, expected $expected")
                        position = expected
                        if (kind == Kind.Completed) kind = Kind.Paused
                    }
                    runCurrent()
                    verify("seek")
                }
                pick < 50 -> { // replay
                    player.replay()
                    if (!released && playable()) {
                        position = 0L
                        kind = Kind.Playing
                    }
                    verify("replay")
                }
                pick < 55 -> { // speed
                    val requested: Float = when (random.nextInt(6)) {
                        0 -> Float.NaN
                        1 -> Float.POSITIVE_INFINITY
                        2 -> Float.NEGATIVE_INFINITY
                        3 -> -2f
                        else -> random.nextFloat() * 4f
                    }
                    player.setPlaybackSpeed(requested)
                    if (!requested.isNaN()) speed = requested.coerceIn(min, max)
                    verify("speed $requested")
                }
                pick < 60 -> { // unload
                    player.unload()
                    if (!released && kind != Kind.Idle) {
                        kind = Kind.Idle
                        position = 0L
                        duration = 0L
                    }
                    verify("unload")
                }
                pick < 68 -> { // the stream ends
                    val playing: Boolean = kind == Kind.Playing
                    if (playing) engine.completePlayback()
                    runCurrent()
                    if (playing) {
                        kind = Kind.Completed
                        position = duration
                    }
                    verify("completion")
                }
                pick < 74 -> { // playback fails after loading
                    val live: Boolean = !released && kind in setOf(Kind.Ready, Kind.Playing, Kind.Paused)
                    val cause = IllegalStateException("playback $step")
                    if (live) {
                        engine.failPlayback(cause)
                        runCurrent()
                        kind = Kind.Error
                        errorCause = cause
                    }
                    verify("failure")
                }
                pick < 78 -> { // a late completion or failure after an unload or a release: dropped
                    if (released || kind == Kind.Idle) {
                        val before: PlayerState = player.stateFlow.value
                        if (random.nextBoolean()) engine.completePlayback() else engine.failPlayback(IllegalStateException("late"))
                        runCurrent()
                        assertEquals(before, player.stateFlow.value, "seed=$seed step=$step: a late callback changed the state")
                        engine.advancePositionTo(0L)
                    }
                    verify("late")
                }
                pick < 92 -> { // time passes, with and without the events the player does not observe
                    // Audio focus loss and gain, a route change and an interruption are NOT observed (05-platform-notes):
                    // the player must neither pause nor resume, so nothing but the position poll happens.
                    val move: Long = random.nextLong(0, 3_000)
                    if (kind == Kind.Playing && duration > 0L) {
                        engine.advancePositionTo((engine.positionMs + move).coerceAtMost(duration - 1L))
                    } else if (kind == Kind.Playing) {
                        engine.advancePositionBy(move)
                    }
                    advanceTimeBy(150L)
                    runCurrent()
                    if (kind == Kind.Playing) {
                        position = engine.positionMs
                        val shown: PlayerState = player.stateFlow.value
                        assertIs<PlayerState.Playing>(shown, "seed=$seed step=$step: an event the player does not observe changed the state")
                        if (shown.currentPosition != position) fail("polled position ${shown.currentPosition} vs ${position}")
                        if (player.playbackPositionFlow.value != position) fail("position flow ${player.playbackPositionFlow.value} vs $position")
                    }
                    verify("time/events")
                }
                pick < 98 -> { // a burst of transport calls on the same tick
                    player.play(); player.pause(); player.play(); player.seekTo(random.nextLong(0, 50_000)); player.pause()
                    runCurrent()
                    if (!released) {
                        if (kind == Kind.Ready || kind == Kind.Paused || kind == Kind.Completed) {
                            // play then pause: from Ready/Paused/Completed this ends Paused at the sought position.
                            kind = Kind.Paused
                            position = engine.positionMs
                        } else if (kind == Kind.Playing) {
                            kind = Kind.Paused
                            position = engine.positionMs
                        }
                    }
                    verify("burst")
                }
                else -> { // release (sticky), then keep going on a dead player
                    if (!released) {
                        player.release()
                        released = true
                        kind = Kind.Idle
                        position = 0L
                        val releases: Int = engine.releaseCount
                        player.release()
                        assertEquals(releases, engine.releaseCount, "seed=$seed: a second release touched the engine")
                    }
                    verify("release")
                }
            }
        }

        // Resources and history.
        player.release()
        val releasesAfter: Int = engine.releaseCount
        player.release()
        assertEquals(releasesAfter, engine.releaseCount, "seed=$seed: release is not idempotent")
        assertTrue(!engine.hasListener, "seed=$seed: listener left")
        assertTrue(!engine.isPlaying, "seed=$seed: engine still playing after release")
        var previous: Kind = Kind.Idle
        for (state in history.filter { it != PlayerState.Preparing }) {
            val next: Kind = kindOf(state)
            if (next != previous) {
                assertTrue(next in allowed.getValue(previous), "seed=$seed: the state walked $previous -> $next ($state)")
            }
            previous = next
        }
        if (releasedError != null) assertSame(releasedError, player.stateFlow.value.takeIf { it is PlayerState.Error } ?: releasedError)
    }

    @Test
    fun `seed 5 survives 300 steps of calls and platform events and matches the model`() = run(5, 300)

    @Test
    fun `seed 17 survives 300 steps of calls and platform events and matches the model`() = run(17, 300)

    @Test
    fun `seed 404 survives 300 steps of calls and platform events and matches the model`() = run(404, 300)

    @Test
    fun `seed 2024 survives 300 steps of calls and platform events and matches the model`() = run(2024, 300)

    @Test
    fun `seed 31337 survives 300 steps of calls and platform events and matches the model`() = run(31337, 300)
}
