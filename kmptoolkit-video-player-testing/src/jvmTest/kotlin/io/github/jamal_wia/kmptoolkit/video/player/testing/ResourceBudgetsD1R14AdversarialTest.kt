package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.IOException
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout

/**
 * D1 round 14, question 1 (leaks and resource budgets): what the player keeps after a prepare ended in each of its ways,
 * after a release, and over many cycles. Derived from `03-guide.md` ("a player owns a decoder, a surface binding and a
 * polling coroutine" and `release()` frees them), `04-api-reference.md` (a cancelled or replaced load is unwound; release
 * detaches from the engine, cancels polling; the engine is disposed exactly once; nothing reaches the engine after) and the
 * engine contract (one load at a time: every load is preceded by a release of the previous source).
 *
 * Virtual time and an Unconfined caller: no thread, no sleep. The garbage collector is asked synchronously
 * (`System.gc()`), bounded by a count, never by a wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceBudgetsD1R14AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")

    private class Env {
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
        val engine: ScriptedEngineD1R11 = ScriptedEngineD1R11()
        val escaped: MutableList<Throwable> = mutableListOf()
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )
    }

    // --- the caller's job keeps no child of the prepare, however the prepare ended ---

    private suspend fun callerChildren(): Int = currentCoroutineContext()[Job]!!.children.count()

    @Test
    fun `a prepare leaves no child job on its caller after success or failure`() = runBlocking {
        withTimeout(30_000L) {
            val env = Env()
            val seen: MutableList<Int> = mutableListOf()
            launch(Dispatchers.Unconfined) {
                env.player.prepare(source)
                seen += callerChildren()
                env.engine.loadAction = { throw IOException("down") }
                env.player.prepare(source)
                assertTrue(env.player.stateFlow.value is VideoPlayerState.Error, "state ${env.player.stateFlow.value}")
                seen += callerChildren()
            }.join()
            env.player.release()
            assertEquals(listOf(0, 0), seen)
        }
    }

    @Test
    fun `a prepare cancelled by its caller leaves no child job and ends Idle with the engine freed`() = runBlocking {
        withTimeout(30_000L) {
            val env = Env()
            val gate = CompletableDeferred<Unit>()
            env.engine.loadAction = { gate.await() }
            var after: Int = -1
            val caller: Job = launch(Dispatchers.Unconfined) {
                try {
                    env.player.prepare(source)
                    fail("a cancelled prepare must throw")
                } catch (cancelled: CancellationException) {
                    after = 0
                    throw cancelled
                }
            }
            assertEquals(VideoPlayerState.Preparing, env.player.stateFlow.value)
            caller.cancelAndJoin()
            // A job completes only when its children have: the join returning is the proof, the count states it.
            assertTrue(caller.isCompleted && caller.children.none(), "the cancelled caller still has child jobs")
            assertEquals(0, after)
            assertEquals(VideoPlayerState.Idle, env.player.stateFlow.value)
            assertEquals("release", env.engine.log.last(), "the engine log ${env.engine.log}")
            env.player.release()
        }
    }

    @Test
    fun `a prepare replaced by a newer one and one ended by unload or release leave no child job`() = runBlocking {
        withTimeout(30_000L) {
            val env = Env()
            val gates: MutableList<CompletableDeferred<Unit>> = mutableListOf()
            env.engine.loadAction = { CompletableDeferred<Unit>().also { gates += it }.await() }
            val after: MutableMap<String, Int> = mutableMapOf()
            val first: Job = launch(Dispatchers.Unconfined) {
                env.player.prepare(source)
                after["replaced"] = 0
            }
            val second: Job = launch(Dispatchers.Unconfined) {
                env.player.prepare(source)
                after["winner"] = callerChildren()
            }
            // The first load was cancelled by the second prepare and has unwound; the second runs.
            first.join()
            assertTrue(first.isCompleted && first.children.none(), "the replaced caller still has child jobs")
            gates.last().complete(Unit)
            second.join()
            assertEquals(0, after["winner"])
            // unload while a load is in flight
            gates.clear()
            val third: Job = launch(Dispatchers.Unconfined) {
                env.player.prepare(source)
                after["unloaded"] = 0
            }
            env.player.unload()
            third.join()
            assertTrue(third.isCompleted && third.children.none(), "the unloaded caller still has child jobs")
            // release while a load is in flight
            val fourth: Job = launch(Dispatchers.Unconfined) {
                env.player.prepare(source)
                after["released"] = 0
            }
            env.player.release()
            fourth.join()
            assertTrue(fourth.isCompleted && fourth.children.none(), "the released caller still has child jobs")
            assertEquals(1, env.engine.log.count { it == "dispose" })
            assertEquals(emptyList(), env.engine.callsAfterDispose())
            assertEquals(emptyList(), env.escaped)
        }
    }

    // --- a released player is collectable, whatever its prepare went through ---

    /** Builds a player, takes it through [path], releases it, and returns only weak handles. The engine stays strongly held. */
    private fun releasedPlayerRef(
        engine: ScriptedEngineD1R11,
        scheduler: TestCoroutineScheduler,
        collectors: CoroutineScope,
        path: (VideoPlayer, ScriptedEngineD1R11, TestCoroutineScheduler) -> Unit,
    ): WeakReference<Any> {
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler),
        )
        // The flows are taken out first: a collector's lambda must not capture the player itself.
        val flows: List<kotlinx.coroutines.flow.Flow<Any?>> = listOf(
            player.stateFlow,
            player.playbackPositionFlow,
            player.bufferedPositionFlow,
            player.isBufferingFlow,
            player.videoSizeFlow,
        )
        for (flow in flows) collectors.launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { } }
        path(player, engine, scheduler)
        player.release()
        return WeakReference(player)
    }

    private fun assertCollected(label: String, ref: WeakReference<Any>) {
        var rounds: Int = 0
        while (ref.get() != null && rounds < 40) {
            System.gc()
            // Allocation pressure so a conservative collector also runs a cycle; no waiting on the code under test.
            @Suppress("UNUSED_VARIABLE") val pressure: ByteArray = ByteArray(1 shl 20)
            rounds++
        }
        if (ref.get() != null) fail("$label: a released player is still reachable after $rounds collections")
    }

    private fun collectabilityCase(label: String, path: (VideoPlayer, ScriptedEngineD1R11, TestCoroutineScheduler) -> Unit) {
        val engine = ScriptedEngineD1R11().apply { buffered = 1_000L }
        val scheduler = TestCoroutineScheduler()
        val collectors = CoroutineScope(Job() + Dispatchers.Unconfined)
        val ref: WeakReference<Any> = releasedPlayerRef(engine, scheduler, collectors, path)
        // A cancelled polling coroutine is resumed through the consumer's dispatcher: on a hand-stepped scheduler that is a
        // task that holds the player until the test runs it (a real dispatcher runs it at once).
        scheduler.runCurrent()
        assertCollected(label, ref)
        // The engine and the scheduler outlived the player on purpose; touch them so the compiler keeps them.
        assertEquals(1, engine.log.count { it == "dispose" }, "$label: the engine was disposed once, log ${engine.log}")
        assertTrue(scheduler.currentTime >= 0L)
        collectors.cancel()
    }

    @Test
    fun `a released player is collectable after a play with live collectors on every source flow`() {
        collectabilityCase("playing") { player, engine, scheduler ->
            runBlocking { player.prepare(source) }
            player.play()
            engine.position = 500L
            scheduler.advanceTimeBy(350L)
            scheduler.runCurrent()
            checkNotNull(engine.sink).onVideoSizeChanged(VideoSize(16, 9))
            checkNotNull(engine.sink).onBufferingChanged(true)
        }
    }

    @Test
    fun `a released player is collectable after a failed prepare`() {
        collectabilityCase("failed prepare") { player, engine, _ ->
            engine.loadAction = { throw IOException("down") }
            runBlocking { player.prepare(source) }
            check(player.stateFlow.value is VideoPlayerState.Error)
        }
    }

    @Test
    fun `a released player is collectable after a cancelled prepare`() {
        collectabilityCase("cancelled prepare") { player, engine, _ ->
            val gate = CompletableDeferred<Unit>()
            engine.loadAction = { gate.await() }
            runBlocking {
                val caller: Job = launch(Dispatchers.Unconfined) { player.prepare(source) }
                caller.cancelAndJoin()
            }
        }
    }

    @Test
    fun `a released player is collectable after a prepare replaced by another and after an unload`() {
        collectabilityCase("replaced prepare then unload") { player, engine, _ ->
            val gates: MutableList<CompletableDeferred<Unit>> = mutableListOf()
            engine.loadAction = { CompletableDeferred<Unit>().also { gates += it }.await() }
            runBlocking {
                val first: Job = launch(Dispatchers.Unconfined) { player.prepare(source) }
                val second: Job = launch(Dispatchers.Unconfined) { player.prepare(source) }
                first.join()
                gates.last().complete(Unit)
                second.join()
            }
            player.play()
            player.unload()
        }
    }

    @Test
    fun `a released player is collectable after a release called while a load is in flight`() {
        collectabilityCase("release in flight") { player, engine, _ ->
            val gate = CompletableDeferred<Unit>()
            engine.loadAction = { gate.await() }
            runBlocking {
                val caller: Job = launch(Dispatchers.Unconfined) { player.prepare(source) }
                player.release()
                caller.join()
            }
        }
    }

    // --- what a failed or replaced source drags along is let go ---

    private class Payload

    private fun failureRef(env: Env): WeakReference<Any> {
        val payload = Payload()
        val cause = IOException("down " + payload.hashCode()).also { it.initCause(RuntimeException(payload.toString())) }
        val ref: WeakReference<Any> = WeakReference(cause)
        env.engine.loadAction = { throw cause }
        runBlocking { env.player.prepare(source) }
        check((env.player.stateFlow.value as VideoPlayerState.Error).cause === cause)
        env.engine.loadAction = {}
        return ref
    }

    @Test
    fun `the cause of an Error is let go once the next prepare or an unload replaces it`() {
        for (leaving in listOf("prepare", "unload")) {
            val env = Env()
            val ref: WeakReference<Any> = failureRef(env)
            when (leaving) {
                "prepare" -> runBlocking { env.player.prepare(source) }
                else -> env.player.unload()
            }
            assertCollected("cause after $leaving", ref)
            env.player.release()
        }
    }

    private fun sourceRef(env: Env, index: Int): WeakReference<Any> {
        val s = VideoSource.Remote("https://example.test/$index.mp4", mapOf("X" to "$index"))
        runBlocking { env.player.prepare(s) }
        return WeakReference(s)
    }

    @Test
    fun `the player does not hold on to a source it was asked to prepare once the next one is prepared`() {
        val env = Env()
        val refs: MutableList<WeakReference<Any>> = mutableListOf()
        repeat(50) { refs += sourceRef(env, it) }
        env.engine.loadAction = {}
        // The scripted engine keeps no source: whatever is still reachable, the player holds.
        val held: Int = run {
            for (r in refs) assertCollectedQuiet(r)
            refs.count { it.get() != null }
        }
        env.player.release()
        assertEquals(0, held, "sources still reachable through the player after 50 prepares")
    }

    private fun assertCollectedQuiet(ref: WeakReference<Any>) {
        var rounds: Int = 0
        while (ref.get() != null && rounds < 5) {
            System.gc()
            rounds++
        }
    }

    // --- a long churn: balance of engine calls, no growth, no call after the release ---

    @Test
    fun `ten thousand cycles keep the engine balanced and the caller job clean`() = runBlocking {
        withTimeout(120_000L) {
            val env = Env()
            val rnd = Random(14_014L)
            var cycles: Int = 0
            val errors: MutableList<String> = mutableListOf()
            launch(Dispatchers.Unconfined) {
                repeat(10_000) { cycle ->
                    cycles = cycle + 1
                    val fail: Boolean = rnd.nextInt(10) == 0
                    env.engine.loadAction = { if (fail) throw IOException("c$cycle") }
                    env.player.prepare(source)
                    when (rnd.nextInt(5)) {
                        0 -> env.player.play()
                        1 -> { env.player.play(); env.engine.position = 1_000L; env.scheduler.advanceTimeBy(250L); env.scheduler.runCurrent() }
                        2 -> { env.player.play(); env.player.pause(); env.player.stop() }
                        3 -> env.player.seekForward(Long.MAX_VALUE)
                        else -> Unit
                    }
                    if (rnd.nextBoolean()) env.player.unload()
                    if (callerChildren() != 0) errors += "cycle $cycle: caller has ${callerChildren()} child jobs"
                }
            }.join()
            env.player.release()
            assertEquals(10_000, cycles)
            assertEquals(emptyList(), errors.take(5))
            val log: List<String> = env.engine.log
            // Engine contract: between two loads the previous source was released.
            var loadedNow: Boolean = false
            for ((i, call) in log.withIndex()) {
                when (call) {
                    "load" -> {
                        if (loadedNow) fail("load #$i without a release of the previous source")
                        loadedNow = true
                    }
                    "release" -> loadedNow = false
                }
            }
            assertTrue(!loadedNow, "the last source was left unreleased")
            assertEquals(2, log.count { it == "setListener" }, "listener registrations: install and detach")
            assertEquals(1, log.count { it == "dispose" })
            assertEquals(emptyList(), env.engine.callsAfterDispose())
            // No poll survives the release: a lot of virtual time later the engine is not touched.
            val size: Int = log.size
            env.scheduler.advanceTimeBy(1_000_000L)
            env.scheduler.runCurrent()
            assertEquals(size, env.engine.log.size, "engine calls after the release: ${env.engine.log.drop(size)}")
            assertEquals(emptyList(), env.escaped)
        }
    }
}
