package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** An engine that counts the position polls it answers and keeps no reference to the sources it was given. */
private class CountingEngine(private val inner: FakePlaybackEngine = FakePlaybackEngine(durationMs = 30_000L)) :
    PlaybackEngine by inner {
    var polls: Int = 0
    val driver: FakePlaybackEngine get() = inner

    // The fake's documented `loadedSources` log grows by design (every source ever loaded): emptied here so that what
    // is measured is the player, not the test double's own log.
    override suspend fun load(source: AudioSource) {
        try {
            inner.load(source)
        } finally {
            inner.loadedSources.clear()
        }
    }

    override fun positionMs(): Long {
        polls++
        return inner.positionMs()
    }
}

/**
 * Round 11, Q3 for the player: 10 000 prepare, play, stop, unload, completion, failure cycles on one player, then
 * 2 000 players created and released. Nothing may grow: the polling work per second of playing stays the same from
 * the first cycle to the last (a leaked poller would multiply it) and stops for good at stop, unload and release;
 * the sources, errors and players of finished cycles are collectable (weak references).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSoakR11Test {

    private fun collectGarbage() {
        repeat(5) {
            System.gc()
            System.runFinalization()
        }
    }

    @Test
    fun `ten thousand cycles on one player keep the polling constant and retain no source or error`() = runTest {
        val engine = CountingEngine()
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        val random = Random(SEED)
        val sources: MutableList<WeakReference<AudioSource>> = mutableListOf()
        val errors: MutableList<WeakReference<Throwable>> = mutableListOf()
        val pollsPerSecond: MutableList<Int> = mutableListOf()
        try {
            repeat(CYCLES) { cycle: Int ->
                val source: AudioSource = AudioSource.Remote("https://example.test/soak-$cycle.mp3")
                sources += WeakReference(source)
                val failing: Boolean = cycle % 17 == 5
                if (failing) {
                    val cause = IllegalStateException("load $cycle")
                    errors += WeakReference(cause)
                    engine.driver.loadFailure = cause
                }
                val job = launch { player.prepare(source) }
                runCurrent()
                assertTrue(job.isCompleted, "cycle $cycle: prepare did not finish")
                engine.driver.loadFailure = null
                if (failing) {
                    assertTrue(player.stateFlow.value is PlayerState.Error, "cycle $cycle ${player.stateFlow.value}")
                } else {
                    player.play()
                    assertTrue(player.stateFlow.value is PlayerState.Playing, "cycle $cycle ${player.stateFlow.value}")
                    if (cycle % 500 == 0) {
                        val before: Int = engine.polls
                        advanceTimeBy(1_000L)
                        runCurrent()
                        pollsPerSecond += engine.polls - before
                    } else {
                        advanceTimeBy(250L)
                        runCurrent()
                    }
                    when (random.nextInt(5)) {
                        0 -> player.stop()
                        1 -> player.unload()
                        2 -> { engine.driver.completePlayback(); runCurrent() }
                        3 -> { player.pause(); player.seekTo(1_000L); player.play(); player.pause() }
                        else -> player.replay().also { player.stop() }
                    }
                }
                // Between cycles nothing is playing: the polling work stops and stays stopped.
                if (cycle % 500 == 0) {
                    player.stop()
                    val before: Int = engine.polls
                    advanceTimeBy(5_000L)
                    runCurrent()
                    assertEquals(before, engine.polls, "cycle $cycle: the player keeps polling an engine it is not playing")
                }
            }
            assertTrue(pollsPerSecond.isNotEmpty() && pollsPerSecond.all { it in pollsPerSecond.first() - 1..pollsPerSecond.first() + 1 },
                "polls per second of playing, sampled every 500 cycles: $pollsPerSecond")
        } finally {
            player.release()
        }
        val before: Int = engine.polls
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(before, engine.polls, "polling after release")
        collectGarbage()
        val aliveSources: Int = sources.count { it.get() != null }
        val aliveErrors: Int = errors.count { it.get() != null }
        assertTrue(aliveSources <= 2, "$aliveSources of ${sources.size} sources are still reachable")
        assertTrue(aliveErrors <= 2, "$aliveErrors of ${errors.size} load errors are still reachable")
    }

    @Test
    fun `two thousand players created and released are all collectable`() = runTest {
        val players: MutableList<WeakReference<AudioPlayer>> = mutableListOf()
        val engines: MutableList<WeakReference<CountingEngine>> = mutableListOf()
        repeat(2_000) { index: Int ->
            val engine = CountingEngine()
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
            players += WeakReference(player)
            engines += WeakReference(engine)
            val job = launch { player.prepare(AudioSource.Remote("https://example.test/churn-$index.mp3")) }
            runCurrent()
            check(job.isCompleted)
            player.play()
            advanceTimeBy(120L)
            runCurrent()
            if (index % 3 == 0) player.unload()
            player.release()
        }
        advanceTimeBy(5_000L)
        runCurrent()
        collectGarbage()
        val alivePlayers: Int = players.count { it.get() != null }
        val aliveEngines: Int = engines.count { it.get() != null }
        assertTrue(alivePlayers <= 2, "$alivePlayers of 2000 released players are still reachable")
        assertTrue(aliveEngines <= 2, "$aliveEngines of 2000 engines of released players are still reachable")
    }

    private companion object {
        const val CYCLES: Int = 10_000
        const val SEED: Long = 2222L
    }
}
