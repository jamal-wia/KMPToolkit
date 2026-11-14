package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * [VirtualFxHost], [NeverDispatcher] and [fxModel] checked on their own: the model tier trusts them, so
 * nothing else in the tier may be the first to find a defect in them. No JavaFX, no real media, no real
 * time except the hang bounds of the two tests that wait on a thread or on a load that must fail.
 */
class VirtualFxHostTest {

    private val milli: Long = 1_000_000L

    // -- clock --

    @Test
    fun theClockStartsAtTheDefaultEpochFarFromZero() {
        val host = VirtualFxHost()

        assertEquals(1_000_000_000_000L, host.nanoTime())
        assertEquals(0L, host.elapsedNanos)
    }

    @Test
    fun theEpochIsConfigurableIncludingANegativeOne() {
        assertEquals(-5_000L, VirtualFxHost(epochNanos = -5_000L).nanoTime())
        val host = VirtualFxHost(epochNanos = -5_000L)
        host.advanceBy(2)
        assertEquals(-5_000L + 2 * milli, host.nanoTime())
    }

    @Test
    fun theClockMovesOnlyWhenTimeIsDriven() {
        val host = VirtualFxHost()
        host.after(50.0) { }
        host.postLater { }
        val before: Long = host.nanoTime()

        host.step()

        assertEquals(before, host.nanoTime(), "a block due now does not move the clock")
        host.step()
        assertEquals(before + 50 * milli, host.nanoTime(), "taking a later block moves the clock to its due time")
    }

    // -- the ordering rule is the production FxQueue --

    @Test
    fun aCallOutsideTheFxThreadIsQueuedNeverInline() {
        val host = VirtualFxHost()
        var ran = false

        host.post { ran = true }

        assertFalse(ran, "the test body is another thread: even with nothing queued the block is queued")
        assertEquals(1, host.pendingCount)
        assertTrue(host.step())
        assertTrue(ran)
    }

    @Test
    fun aPostInsideOnFxWithNothingQueuedRunsInline() {
        val host = VirtualFxHost()
        var ranInline = false

        host.onFx { host.post { ranInline = true }; assertTrue(ranInline, "inline: before post returned") }

        assertEquals(0, host.pendingCount, "an inline block is never queued")
    }

    @Test
    fun aPostInsideOnFxBehindAQueuedBlockIsQueuedBehindIt() {
        val host = VirtualFxHost()
        val order: MutableList<String> = mutableListOf()
        host.post { order += "earlier" }

        host.onFx { host.post { order += "later" } }

        assertEquals(emptyList(), order, "nothing ran yet: the inline path would have overtaken the queued block")
        host.runUntilIdle()
        assertEquals(listOf("earlier", "later"), order)
    }

    @Test
    fun aPostLaterInsideOnFxIsAlwaysQueued() {
        val host = VirtualFxHost()
        var ran = false

        host.onFx { host.postLater { ran = true } }

        assertFalse(ran)
        assertEquals(1, host.pendingCount)
        host.runUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun theFxThreadIsTheDrivingThreadOnlyWhileABlockRuns() {
        val host = VirtualFxHost()
        var insideOnFx = false
        var insideBlock = false
        assertFalse(host.isFxThread)

        host.onFx { insideOnFx = host.isFxThread }
        host.post { insideBlock = host.isFxThread }
        host.runUntilIdle()

        assertTrue(insideOnFx)
        assertTrue(insideBlock)
        assertFalse(host.isFxThread, "after the block the thread is another thread again")
    }

    @Test
    fun aCallFromARealThreadWhileABlockRunsIsNotOnTheFxThread() {
        val host = VirtualFxHost()
        val seenFromOtherThread: FutureTask<Boolean> = FutureTask { host.isFxThread }
        host.post {
            Thread(seenFromOtherThread).start()
            assertFalse(seenFromOtherThread.get(10, TimeUnit.SECONDS), "only the driving thread is the FX thread")
        }

        host.runUntilIdle()
    }

    @Test
    fun aCallThroughCallSuspendsUntilTheHostRunsIt() {
        val host = VirtualFxHost()

        val result: Int = runBlocking {
            val call = async { host.call { 42 } }
            yield()
            assertFalse(call.isCompleted, "the block has not run: the test body is another thread")
            host.runUntilIdle()
            call.await()
        }

        assertEquals(42, result)
    }

    @Test
    fun aCallThroughCallPropagatesTheBlocksException() {
        val host = VirtualFxHost()

        val failure: IllegalStateException = assertFailsWith {
            runBlocking {
                val call = async { host.call<Int> { throw IllegalStateException("from the fx block") } }
                yield()
                host.runUntilIdle()
                call.await()
            }
        }

        assertEquals("from the fx block", failure.message)
    }

    // -- order and virtual time --

    @Test
    fun blocksDueAtTheSameTimeRunInTheOrderTheyWereQueued() {
        val host = VirtualFxHost()
        val order: MutableList<Int> = mutableListOf()

        for (i in 1..5) host.postLater { order += i }
        host.runUntilIdle()

        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    @Test
    fun anAfterRunsByDueTimeNotByTheOrderItWasQueued() {
        val host = VirtualFxHost()
        val order: MutableList<String> = mutableListOf()

        host.after(30.0) { order += "30" }
        host.after(10.0) { order += "10" }
        host.postLater { order += "now" }
        host.after(10.0) { order += "10 again" }
        host.runUntilIdle()

        assertEquals(listOf("now", "10", "10 again", "30"), order)
    }

    @Test
    fun anAfterIsDueTheDelayFromNowWithTheJitterOff() {
        val host = VirtualFxHost()
        host.advanceBy(7)

        host.after(2.5) { }

        assertEquals(host.nanoTime() + 2_500_000L, host.nextDueNanos)
    }

    @Test
    fun stepRunsOneBlockAndAnEmptyQueueRunsNone() {
        val host = VirtualFxHost()
        assertFalse(host.step())
        assertNull(host.nextDueNanos)
        host.postLater { }
        host.postLater { }

        assertTrue(host.step())

        assertEquals(1, host.pendingCount)
        assertEquals(1, host.tasksRun)
    }

    @Test
    fun advanceByRunsWhatIsDueInTheWindowAndLeavesTheClockExactlyThere() {
        val host = VirtualFxHost()
        val ran: MutableList<String> = mutableListOf()
        host.after(10.0) { ran += "10"; host.after(10.0) { ran += "10+10" } }
        host.after(21.0) { ran += "21" }
        val start: Long = host.nanoTime()

        host.advanceBy(20)

        assertEquals(listOf("10", "10+10"), ran, "a block queued inside the window and due inside it runs too")
        assertEquals(start + 20 * milli, host.nanoTime())
        assertEquals(1, host.pendingCount, "the block due at 21 ms is untouched")
    }

    @Test
    fun advanceByWithNothingQueuedStillMovesTheClock() {
        val host = VirtualFxHost()

        host.advanceBy(1_500)

        assertEquals(host.epochNanos + 1_500 * milli, host.nanoTime())
    }

    @Test
    fun runUntilIdleDrainsChainsAndLeavesTheClockAtTheLastBlock() {
        val host = VirtualFxHost()
        val ran: MutableList<Long> = mutableListOf()
        host.after(5.0) { ran += host.elapsedNanos; host.after(5.0) { ran += host.elapsedNanos } }

        host.runUntilIdle()

        assertEquals(listOf(5 * milli, 10 * milli), ran)
        assertEquals(0, host.pendingCount)
        assertEquals(10 * milli, host.elapsedNanos)
    }

    @Test
    fun runUntilIdleFailsOnWorkThatNeverEndsAndNamesTheSeed() {
        val host = VirtualFxHost(seed = 777L)
        fun pulse() { host.after(16.0) { pulse() } }
        pulse()

        val failure: IllegalStateException = assertFailsWith { host.runUntilIdle(limitMs = 1_000) }

        assertTrue("seed=777" in failure.message.orEmpty(), failure.message)
        assertTrue("never idle" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aBlockThatKeepsQueueingItselfWithoutTimePassingFails() {
        val host = VirtualFxHost(maxTasksPerCall = 100)
        fun again() { host.postLater { again() } }
        again()

        val failure: IllegalStateException = assertFailsWith { host.runUntilIdle() }

        assertTrue("keeps queueing itself" in failure.message.orEmpty(), failure.message)
    }

    // -- jitter --

    @Test
    fun theAfterJitterStaysInItsRangeAndIsReproducibleFromTheSeed() {
        // Per-call jitter, read one block at a time.
        fun draws(seed: Long): List<Long> {
            val host = VirtualFxHost(seed = seed, jitterMaxMs = 17.0)
            return (1..40).map {
                host.after(0.0) { }
                val extra: Long = host.nextDueNanos!! - host.nanoTime()
                host.step()
                extra
            }
        }

        val first: List<Long> = draws(seed = 42L)

        assertEquals(first, draws(seed = 42L), "the same seed draws the same jitter")
        assertNotEquals(first, draws(seed = 43L), "another seed draws another jitter")
        assertTrue(first.all { it in 0L..17 * milli }, "every draw is within 0..17 ms: $first")
        assertTrue(first.toSet().size > 1, "the jitter varies: $first")
    }

    @Test
    fun theJitterIsOffByDefault() {
        val host = VirtualFxHost()

        repeat(10) {
            host.after(3.0) { }
            assertEquals(host.nanoTime() + 3 * milli, host.nextDueNanos)
            host.step()
        }
    }

    // -- beforeEachTask --

    @Test
    fun beforeEachTaskRunsBeforeEveryBlockWithTheNumberRunSoFar() {
        val host = VirtualFxHost()
        val order: MutableList<String> = mutableListOf()
        host.beforeEachTask { index -> order += "hook$index" }
        host.postLater { order += "a" }
        host.postLater { order += "b" }

        host.runUntilIdle()

        assertEquals(listOf("hook0", "a", "hook1", "b"), order)
    }

    @Test
    fun aCallMadeByTheHookIsFromAnotherThreadSoItIsQueuedBehindWhatIsDue() {
        val host = VirtualFxHost()
        val order: MutableList<String> = mutableListOf()
        var hookSawFxThread = true
        host.beforeEachTask { index ->
            if (index == 0) {
                hookSawFxThread = host.isFxThread
                host.post { order += "injected" }
            }
        }
        host.postLater { order += "first" }
        host.postLater { order += "second" }

        host.runUntilIdle()

        assertFalse(hookSawFxThread)
        assertEquals(listOf("first", "second", "injected"), order)
    }

    @Test
    fun aCallInjectedAtEveryPointLandsAtThatPointAndNowhereElse() {
        val blocks = 4
        for (point in 0..blocks) {
            val host = VirtualFxHost()
            val order: MutableList<String> = mutableListOf()
            for (i in 1..blocks) host.after(i * 10.0) { order += "t$i" }
            if (point < blocks) {
                host.beforeEachTask { index -> if (index == point) host.post { order += "inj" } }
                host.runUntilIdle()
            } else {
                // After the last block there is no hook call left: the call comes from the test body.
                host.runUntilIdle()
                host.post { order += "inj" }
                host.runUntilIdle()
            }

            val expected: List<String> = (1..blocks).map { "t$it" }.toMutableList().also { it.add(point, "inj") }
            assertEquals(expected, order, "injected before block $point (seed=${host.seed})")
        }
    }

    @Test
    fun aHookThatQueuesAnEarlierBlockMakesItRunBeforeThePendingTimer() {
        val host = VirtualFxHost()
        val order: MutableList<String> = mutableListOf()
        host.after(50.0) { order += "timer" }
        host.beforeEachTask { index -> if (index == 0) host.post { order += "injected" } }

        host.runUntilIdle()

        assertEquals(listOf("injected", "timer"), order, "the call arrives before the timer fires, so it runs first")
        assertEquals(50 * milli, host.elapsedNanos)
    }

    @Test
    fun aNullHookRemovesIt() {
        val host = VirtualFxHost()
        var calls = 0
        host.beforeEachTask { calls++ }
        host.postLater { }
        host.runUntilIdle()
        host.beforeEachTask(null)
        host.postLater { }

        host.runUntilIdle()

        assertEquals(1, calls)
    }

    // -- failures and re-entrancy --

    @Test
    fun aBlockThatThrowsPropagatesAndLeavesTheHostUsable() {
        val host = VirtualFxHost()
        var later = false
        host.postLater { error("boom") }
        host.postLater { later = true }

        val failure: IllegalStateException = assertFailsWith { host.runUntilIdle() }

        assertEquals("boom", failure.message)
        assertFalse(host.isFxThread, "the host restored its FX-thread state")
        assertEquals(1, host.pendingCount, "the failed block is gone, the next is still queued")
        host.runUntilIdle()
        assertTrue(later)
    }

    @Test
    fun drivingTimeFromInsideABlockIsRefused() {
        val host = VirtualFxHost()
        host.postLater { host.advanceBy(1) }

        val failure: IllegalStateException = assertFailsWith { host.runUntilIdle() }

        assertTrue("already being driven" in failure.message.orEmpty(), failure.message)
        host.runUntilIdle()
    }

    // -- threads --

    @Test
    fun hasExitedNowIsFalseFromAnyThread() {
        val host = VirtualFxHost()
        assertFalse(host.hasExitedNow())
        val fromAThread: FutureTask<Boolean> = FutureTask { host.hasExitedNow() }

        Thread(fromAThread).start()

        assertFalse(fromAThread.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun blocksQueuedFromManyThreadsAreAllKeptAndRunInOneOrderPerThread() {
        val host = VirtualFxHost()
        val perThread = 250
        val threads: Int = 4
        val seen: MutableList<Pair<Int, Int>> = mutableListOf()
        val workers = (0 until threads).map { id ->
            thread {
                for (i in 0 until perThread) host.postLater { seen += id to i }
            }
        }
        workers.forEach { it.join(10_000) }
        assertTrue(workers.none { it.isAlive }, "a worker did not finish its posts in 10 s")

        assertEquals(threads * perThread, host.pendingCount)
        host.runUntilIdle()

        assertEquals(threads * perThread, seen.size)
        for (id in 0 until threads) {
            assertEquals((0 until perThread).toList(), seen.filter { it.first == id }.map { it.second }, "thread $id")
        }
    }

    // -- NeverDispatcher --

    private class StubEngine : VideoPlaybackEngine {
        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) = Unit
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
    }

    @Test
    fun theSharedPlayersOnlyDispatchIsThePositionPollersLaunch() = fxModel {
        val player = videoPlayer(StubEngine())

        runBlocking { player.prepare(VideoSource.Asset("clip.mp4")) }
        player.play()
        player.pause()
        player.play()
        player.seekTo(500L)
        player.setPlaybackSpeed(1.5f)
        player.replay()
        player.pause()

        assertTrue(NeverDispatcher.dispatches.isNotEmpty(), "play() launches the poller on the player's dispatcher")
        assertTrue(NeverDispatcher.dispatches.all { it.isPoller }, "dispatched from elsewhere: ${NeverDispatcher.foreignDispatches}")
        assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "the state is still driven, only the poll is parked")
        player.release()
    }

    @Test
    fun aDispatchThatIsNotThePollersFailsTheModelTest() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                CoroutineScope(NeverDispatcher).launch { }
            }
        }

        assertTrue("not the position poller's launch" in failure.message.orEmpty(), failure.message)
        assertTrue("seed=" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aFailingBlockKeepsItsOwnFailureAndTheCheckIsAddedToIt() {
        val failure: IllegalStateException = assertFailsWith {
            fxModel {
                CoroutineScope(NeverDispatcher).launch { }
                error("the block failed")
            }
        }

        assertEquals("the block failed", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is AssertionError)
    }

    @Test
    fun theRecordIsClearedAtTheStartOfEveryModelTest() {
        assertFailsWith<AssertionError> { fxModel { CoroutineScope(NeverDispatcher).launch { } } }

        fxModel { assertTrue(NeverDispatcher.dispatches.isEmpty(), "a stale dispatch of an earlier test") }
    }

    @Test
    fun anAfterModelCheckRunsAfterTheBlockAndItsFailureFailsTheTest() {
        val order: MutableList<String> = mutableListOf()

        val failure: AssertionError = assertFailsWith {
            fxModel {
                afterModel("a monitor") { order += "check"; throw AssertionError("window violated") }
                order += "block"
            }
        }

        assertEquals(listOf("block", "check"), order)
        assertEquals("window violated", failure.message)
    }

    // -- the load helper --

    private class HostCallingEngine(private val host: VirtualFxHost) : VideoPlaybackEngine {
        val ranOnFxThread: MutableList<Boolean> = mutableListOf()
        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) {
            withContext(Dispatchers.IO) { }
            host.call { ranOnFxThread += host.isFxThread }
            val timer: CompletableDeferred<Unit> = CompletableDeferred()
            host.after(30.0) { ranOnFxThread += host.isFxThread; timer.complete(Unit) }
            timer.await()
        }
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 0L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
    }

    @Test
    fun loadDrivesTheHostUntilTheEngineHasLoaded() = fxModel {
        val engine = HostCallingEngine(host)

        load(engine, VideoSource.Asset("clip.mp4"))

        assertEquals(listOf(true, true), engine.ranOnFxThread, "the call and the timer ran on the model's FX thread")
        assertEquals(30 * milli, host.elapsedNanos, "the timer fired in virtual time, no real 30 ms passed")
        assertEquals(0, host.pendingCount)
    }

    @Test
    fun loadPropagatesTheEnginesFailure() = fxModel {
        val engine = object : VideoPlaybackEngine by StubEngine() {
            override suspend fun load(source: VideoSource) = throw IllegalArgumentException("no such clip")
        }

        val failure: IllegalArgumentException = assertFailsWith { load(engine, VideoSource.Asset("missing.mp4")) }

        assertEquals("no such clip", failure.message)
    }

    @Test
    fun loadFailsAtItsHangBoundWithTheHostStateAndSeed() = fxModel(seed = 99L) {
        val engine = object : VideoPlaybackEngine by StubEngine() {
            override suspend fun load(source: VideoSource) = awaitCancellation()
        }

        val failure: AssertionError = assertFailsWith { load(engine, VideoSource.Asset("clip.mp4"), boundMs = 200L) }

        assertTrue("seed=99" in failure.message.orEmpty(), failure.message)
        assertTrue("did not finish" in failure.message.orEmpty(), failure.message)
    }
}
