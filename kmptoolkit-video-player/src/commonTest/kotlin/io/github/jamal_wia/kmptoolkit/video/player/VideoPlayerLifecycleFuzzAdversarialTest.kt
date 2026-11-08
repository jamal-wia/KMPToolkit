package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lifecycle chaos on the player state machine: seeded random sequences of prepare, cancel, unload,
 * release, transport calls and stale platform callbacks over a scripted engine in virtual time. No
 * single interleaving is claimed; what is checked is what the contracts promise whatever the order:
 * one load at a time, nothing after dispose, nothing released under a running load, nothing left
 * Preparing, no poller left behind, and the settled state follows the last thing that was asked.
 */
class VideoPlayerLifecycleFuzzAdversarialTest {

    private class Plan(val loadMs: Long, val unwindMs: Long, val fails: Boolean, val throwsOnCancel: Boolean)

    private class ChaosEngine : VideoPlaybackEngine {
        val plans: MutableMap<String, Plan> = mutableMapOf()
        var attached: VideoPlaybackEngineListener? = null
        var active: Int = 0
        var maxActive: Int = 0
        var loaded: Boolean = false
        var disposed: Boolean = false
        var disposeCount: Int = 0
        var positionReads: Int = 0
        var playerReleased: Boolean = false
        val violations: MutableList<String> = mutableListOf()

        private fun note(call: String, afterReleaseForbidden: Boolean = true) {
            if (disposed) violations += "$call after dispose"
            else if (playerReleased && afterReleaseForbidden) violations += "$call after the player was released"
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            this.attached = listener
        }

        override suspend fun load(source: VideoSource) {
            note("load")
            val plan: Plan = plans.getValue((source as VideoSource.Remote).url)
            active++
            maxActive = maxOf(maxActive, active)
            loaded = false
            try {
                try {
                    delay(plan.loadMs)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw if (plan.throwsOnCancel) IllegalStateException("cancelled: ${source.url}") else cancellation
                }
                if (plan.fails) throw IllegalStateException(source.url)
                loaded = true
            } finally {
                if (plan.unwindMs > 0L) withContext(NonCancellable) { delay(plan.unwindMs) }
                active--
            }
        }

        override fun start() = note("start")
        override fun pause() = note("pause")
        override fun seekTo(positionMs: Long) = note("seekTo")
        override fun setSpeed(speed: Float) = note("setSpeed")
        override fun setVolume(volume: Float) = note("setVolume")
        override fun setLooping(looping: Boolean) = note("setLooping")
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long {
            note("positionMs")
            positionReads++
            return 1L
        }

        override fun bufferedPositionMs(): Long = 2L

        override fun release() {
            note("release", afterReleaseForbidden = false)
            if (active > 0) violations += "release under a running load"
            loaded = false
            // A platform may report an event from inside the very call that frees it.
            attached?.onBufferingChanged(true)
            attached?.onVideoSizeChanged(VideoSize(1, 1))
            attached?.onCompleted()
        }

        override fun dispose() {
            if (disposed) violations += "second dispose"
            if (active > 0) violations += "dispose under a running load"
            disposed = true
            disposeCount++
        }
    }

    private enum class Expect { None, Ready, Fail }

    private fun TestScope.runSeed(seed: Int, undispatched: Boolean) {
        val random = Random(seed)
        val engine = ChaosEngine()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        val staleListener: VideoPlaybackEngineListener = requireNotNull(engine.attached)
        val jobs: MutableList<Job> = mutableListOf()
        var lastPrepare: Job? = null
        var expect: Expect = Expect.None
        var failUrl: String? = null
        var released = false
        val log: StringBuilder = StringBuilder()

        fun check(step: String) {
            runCurrent()
            if (engine.violations.isNotEmpty()) fail("seed $seed after $step: ${engine.violations} log=$log")
            if (engine.maxActive > 1) fail("seed $seed after $step: two loads at once log=$log")
            val state: VideoPlayerState = player.stateFlow.value
            if (state.isPlayable && !engine.loaded && !released) {
                fail("seed $seed after $step: state $state but the engine has nothing loaded log=$log")
            }
            if (released && engine.attached != null) fail("seed $seed after $step: listener kept after release log=$log")
        }

        repeat(OPS) { index: Int ->
            val op: Int = random.nextInt(100)
            val name: String
            when {
                op < 28 -> {
                    val url = "https://example.test/$seed/$index.mp4"
                    val plan = Plan(
                        loadMs = listOf(0L, 50L, 500L, 2_000L).random(random),
                        unwindMs = listOf(0L, 0L, 100L, 1_000L).random(random),
                        fails = random.nextInt(100) < 15,
                        throwsOnCancel = random.nextInt(100) < 25,
                    )
                    engine.plans[url] = plan
                    name = "prepare($index, fails=${plan.fails}, load=${plan.loadMs}, unwind=${plan.unwindMs})"
                    val job: Job = launch(start = if (undispatched) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
                        player.prepare(VideoSource.Remote(url))
                    }
                    jobs += job
                    if (undispatched && !released) {
                        lastPrepare = job
                        expect = if (plan.fails) Expect.Fail else Expect.Ready
                        failUrl = url
                    }
                }

                op < 38 -> {
                    name = "cancel"
                    if (jobs.isNotEmpty()) {
                        val job: Job = jobs.random(random)
                        if (job === lastPrepare && job.isActive) expect = Expect.None
                        job.cancel()
                    }
                }

                op < 48 -> {
                    name = "unload"
                    player.unload()
                    if (!released) expect = Expect.None
                }

                op < 50 -> {
                    name = "release"
                    player.release()
                    released = true
                    engine.playerReleased = true
                }

                op < 58 -> {
                    name = "play"
                    player.play()
                }

                op < 62 -> {
                    name = "pause"
                    player.pause()
                }

                op < 68 -> {
                    name = "seek"
                    player.seekTo(random.nextLong(-100L, 20_000L))
                }

                op < 71 -> {
                    name = "replay/stop"
                    if (random.nextBoolean()) player.replay() else player.stop()
                }

                op < 74 -> {
                    name = "settings"
                    player.setVolume(random.nextFloat())
                    player.setMuted(random.nextBoolean())
                    player.setRepeatMode(RepeatMode.entries.random(random))
                    player.setPlaybackSpeed(random.nextFloat() * 4f)
                }

                op < 82 -> {
                    name = "stale completion or failure"
                    val before: VideoPlayerState = player.stateFlow.value
                    if (before == VideoPlayerState.Idle || before is VideoPlayerState.Error) {
                        if (random.nextBoolean()) staleListener.onCompleted() else staleListener.onFailed(IllegalStateException("stale"))
                        runCurrent()
                        assertEquals(before, player.stateFlow.value, "seed $seed: a stale callback changed $before log=$log")
                    }
                }

                op < 88 -> {
                    name = "buffering and size"
                    staleListener.onBufferingChanged(random.nextBoolean())
                    staleListener.onVideoSizeChanged(if (random.nextBoolean()) VideoSize(10, 10) else null)
                    runCurrent()
                    if (player.stateFlow.value == VideoPlayerState.Idle) {
                        assertEquals(false, player.isBufferingFlow.value, "seed $seed: idle player buffering log=$log")
                        assertEquals(null, player.videoSizeFlow.value, "seed $seed: idle player has a picture size log=$log")
                    }
                }

                else -> {
                    name = "time"
                    advanceTimeBy(random.nextLong(1L, 1_500L))
                }
            }
            log.append(name).append(';')
            check(name)
        }

        // Quiesce: everything that can finish does, in explicit steps.
        repeat(40) {
            advanceTimeBy(1_000L)
            runCurrent()
        }
        val stuck: List<Job> = jobs.filter { it.isActive }
        if (stuck.isNotEmpty()) fail("seed $seed: ${stuck.size} prepare call(s) never returned log=$log")
        val state: VideoPlayerState = player.stateFlow.value
        if (state == VideoPlayerState.Preparing) fail("seed $seed: stuck in Preparing log=$log")
        assertEquals(0, engine.active, "seed $seed: a load is still running")
        if (engine.violations.isNotEmpty()) fail("seed $seed: ${engine.violations} log=$log")

        if (released) {
            assertEquals(1, engine.disposeCount, "seed $seed: dispose count after release log=$log")
            assertTrue(
                state == VideoPlayerState.Idle || (state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException),
                "seed $seed: state after release was $state log=$log",
            )
        } else {
            assertEquals(0, engine.disposeCount, "seed $seed: disposed without release log=$log")
            if (undispatched) {
                when (expect) {
                    Expect.None -> assertEquals(VideoPlayerState.Idle, state, "seed $seed: log=$log")
                    Expect.Ready -> assertTrue(state.isPlayable, "seed $seed: expected a loaded source, was $state log=$log")
                    Expect.Fail -> assertTrue(
                        state is VideoPlayerState.Error && state.cause.message == failUrl,
                        "seed $seed: expected the failure of $failUrl, was $state log=$log",
                    )
                }
            }
            // No poller may survive whatever ended playback.
            if (state !is VideoPlayerState.Playing) {
                val reads: Int = engine.positionReads
                advanceTimeBy(5_000L)
                runCurrent()
                assertEquals(reads, engine.positionReads, "seed $seed: a position poller outlived playback, state $state log=$log")
            }
        }
        player.release()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, engine.disposeCount, "seed $seed: final dispose count")
        assertTrue(engine.violations.isEmpty(), "seed $seed: ${engine.violations} log=$log")
    }

    @Test
    fun `random lifecycle sequences keep the one load at a time and release guarantees - undispatched callers`() =
        runTest {
            repeat(SEEDS) { seed: Int -> runSeed(seed, undispatched = true) }
        }

    @Test
    fun `random lifecycle sequences keep the one load at a time and release guarantees - dispatched callers`() =
        runTest {
            repeat(SEEDS) { seed: Int -> runSeed(10_000 + seed, undispatched = false) }
        }

    private companion object {
        const val SEEDS: Int = 150
        const val OPS: Int = 60
    }
}
