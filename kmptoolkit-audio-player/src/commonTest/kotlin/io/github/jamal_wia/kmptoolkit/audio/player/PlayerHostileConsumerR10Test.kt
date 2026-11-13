package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 10, hostile consumer of the player: a state collector that calls back into the player from inside the
 * publication of a state (an unconfined collector runs on the publisher's own stack), collectors that throw,
 * collection of a released player, calls on a released player from the released-state collector. The re-entrant
 * answer must equal the answer of a plain call in the same state. Virtual time only; runs on both platforms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHostileConsumerR10Test {

    private val track: AudioSource = AudioSource.Remote("https://example.test/a.mp3")
    private val ops: List<String> =
        listOf("prepare", "play", "pause", "stop", "seek", "fwd", "back", "replay", "speed", "unload", "release")
    private val states: List<String> = listOf("Ready", "Playing", "Paused", "Completed", "Error")

    private fun playerTest(body: suspend TestScope.(RecordingPlaybackEngine, AudioPlayer) -> Unit) = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = createAudioPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    private suspend fun TestScope.reach(engine: RecordingPlaybackEngine, player: AudioPlayer, target: String) {
        when (target) {
            "Ready" -> player.prepare(track)
            "Playing" -> { player.prepare(track); player.play() }
            "Paused" -> { player.prepare(track); player.play(); player.pause() }
            "Completed" -> { player.prepare(track); player.play(); engine.position = engine.duration; engine.listener?.onCompleted() }
            // an engine failure during playback: a failed load would still be in force for the re-entrant prepare
            "Error" -> { player.prepare(track); player.play(); engine.listener?.onFailed(IllegalStateException("engine failed")) }
            else -> error(target)
        }
        runCurrent()
    }

    private suspend fun call(player: AudioPlayer, op: String) {
        when (op) {
            "prepare" -> player.prepare(AudioSource.File("/music/b.mp3"))
            "play" -> player.play()
            "pause" -> player.pause()
            "stop" -> player.stop()
            "seek" -> player.seekTo(4_000L)
            "fwd" -> player.seekForward()
            "back" -> player.seekBackward()
            "replay" -> player.replay()
            "speed" -> player.setPlaybackSpeed(2f)
            "unload" -> player.unload()
            "release" -> player.release()
            else -> error(op)
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): String = try {
        block()
        "ok"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "threw ${e::class.simpleName}"
    }

    private fun name(state: PlayerState): String = when (state) {
        is PlayerState.Error -> "Error"
        else -> state::class.simpleName.toString()
    }

    @Test
    fun `a collector that calls back into the player gets what a plain call gets in that state`() {
        val mismatches: MutableList<String> = mutableListOf()
        for (target: String in states) for (op: String in ops) {
            var plain = ""
            runTest {
                val engine = RecordingPlaybackEngine(duration = 10_000L)
                val player: AudioPlayer = createAudioPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
                try {
                    reach(engine, player, target)
                    val answer: String = attempt { call(player, op) }
                    runCurrent()
                    plain = "$answer then ${name(player.stateFlow.value)} pos=${player.playbackPositionFlow.value} speed=${player.playbackSpeed}"
                } finally {
                    player.release()
                }
            }
            var reentrant = ""
            var neverFired = false
            runTest {
                val engine = RecordingPlaybackEngine(duration = 10_000L)
                val player: AudioPlayer = createAudioPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
                try {
                    var fired = false
                    var answer = "no answer"
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        player.stateFlow.collect { state: PlayerState ->
                            if (!fired && name(state) == target) {
                                fired = true
                                answer = attempt { call(player, op) }
                            }
                        }
                    }
                    reach(engine, player, target)
                    runCurrent()
                    neverFired = !fired
                    reentrant = "$answer then ${name(player.stateFlow.value)} pos=${player.playbackPositionFlow.value} speed=${player.playbackSpeed}"
                } finally {
                    player.release()
                }
            }
            if (neverFired) mismatches += "$target/$op: the collector never saw the state"
            else if (plain != reentrant) mismatches += "$target/$op: plain [$plain], re-entrant [$reentrant]"
        }
        assertEquals(emptyList(), mismatches, "re-entry from a state collector differs from a plain call:\n" + mismatches.joinToString("\n"))
    }

    @Test
    fun `collectors that throw on every emission leave the player working`() = playerTest { engine, player ->
        val caught: MutableList<Throwable> = mutableListOf()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> caught += e },
        )
        repeat(2) { scope.launch { player.stateFlow.collect { throw IllegalStateException("hostile state $it") } } }
        scope.launch { player.playbackPositionFlow.collect { throw IllegalStateException("hostile position $it") } }

        player.prepare(track)
        player.play()
        engine.position = 3_000L
        advanceTimeBy(500)
        runCurrent()

        assertTrue(caught.isNotEmpty(), "the hostile collectors ran")
        assertTrue(player.stateFlow.value is PlayerState.Playing, "state ${player.stateFlow.value}")
        player.pause()
        assertTrue(player.stateFlow.value is PlayerState.Paused, "state ${player.stateFlow.value}")
        scope.cancel()
    }

    @Test
    fun `a position collector that cancels itself stops the polling and a new one restarts it`() = playerTest { engine, player ->
        player.prepare(track)
        player.play()
        val first: kotlinx.coroutines.Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.playbackPositionFlow.collect { }
        }
        engine.position = 2_000L
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2_000L, player.playbackPositionFlow.value)

        first.cancel()
        runCurrent()
        engine.position = 5_000L
        advanceTimeBy(5_000)
        runCurrent()
        // the player keeps its own state coherent whether or not anybody collects the position
        val state: PlayerState = player.stateFlow.value
        assertTrue(state is PlayerState.Playing, "state $state")
        assertTrue(player.playbackPositionFlow.value >= 2_000L, "position ${player.playbackPositionFlow.value}")
    }

    @Test
    fun `calls from the collector of the state a release publishes are ignored or answered by the released error`() =
        playerTest { engine, player ->
            val answers: MutableList<String> = mutableListOf()
            var releasing = false
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.stateFlow.collect { state: PlayerState ->
                    if (releasing && state == PlayerState.Idle && answers.isEmpty()) {
                        answers += attempt { player.play() }
                        answers += attempt { player.seekTo(1L) }
                        answers += attempt { player.prepare(track) }
                        answers += attempt { player.release() }
                    }
                }
            }
            player.prepare(track)
            player.play()
            val loadsBefore: Int = engine.loadedSources.size
            releasing = true
            player.release()
            runCurrent()
            val releasesAfter: Int = engine.releaseCount

            assertEquals(listOf("ok", "ok", "ok", "ok"), answers, "no call may throw into the collector")
            val state: PlayerState = player.stateFlow.value
            assertTrue(state is PlayerState.Error && state.cause is AudioPlayerReleasedException, "state $state")
            assertEquals(loadsBefore, engine.loadedSources.size, "a released player loads nothing")

            // a collector that arrives later sees the one current value and starts no work
            val seen: MutableList<PlayerState> = mutableListOf()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.stateFlow.collect { seen += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.playbackPositionFlow.collect { } }
            advanceTimeBy(1.hours)
            runCurrent()

            assertEquals(1, seen.size, "one value for a late collector: $seen")
            assertEquals(state, player.stateFlow.first())
            assertEquals(releasesAfter, engine.releaseCount, "no further engine release")
            assertEquals(loadsBefore, engine.loadedSources.size, "no load after release")
        }
}
