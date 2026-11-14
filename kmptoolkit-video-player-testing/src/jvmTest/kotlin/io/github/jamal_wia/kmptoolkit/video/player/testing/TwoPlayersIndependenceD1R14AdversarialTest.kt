package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 14, question 4 (several players): a player is independent of every other player (no shared mutable state in the
 * core), so what player A does and shows is the same whether or not a player B runs beside it, is released, or fails.
 *
 * The oracle is a differential: the same seeded operation list for A is run alone and then interleaved with a second
 * player's seeded operations (and releases); everything A shows (every published value of every flow) and everything A's
 * engine was told must be identical. Virtual time, one scheduler per player so one player's ticks cannot move the other's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoPlayersIndependenceD1R14AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/v.mp4")

    private class Op(val kind: Int, val arg: Long)

    private class Side(seedName: String) {
        val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
        val engine: ScriptedEngineD1R11 = ScriptedEngineD1R11().apply { buffered = 1_500L; duration = 8_000L }
        val escaped: MutableList<Throwable> = mutableListOf()
        val player: VideoPlayer = createVideoPlayer(
            engine,
            VideoPlayerConfig(positionUpdateIntervalMs = 100L),
            StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
        )
        val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val history: MutableList<String> = mutableListOf()
        val name: String = seedName

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { history += "state=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.playbackPositionFlow.collect { history += "pos=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.bufferedPositionFlow.collect { history += "buf=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.isBufferingFlow.collect { history += "stall=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.videoSizeFlow.collect { history += "size=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.playbackSpeedFlow.collect { history += "speed=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.volumeFlow.collect { history += "vol=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.isMutedFlow.collect { history += "mute=$it" } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.repeatModeFlow.collect { history += "repeat=$it" } }
        }

        fun close() {
            scope.cancel()
            player.release()
        }
    }

    private fun ops(rnd: Random, count: Int): List<Op> = List(count) { Op(rnd.nextInt(18), rnd.nextLong(0L, 10_000L)) }

    private fun Side.apply(op: Op, source: VideoSource) {
        when (op.kind) {
            0 -> runBlocking { player.prepare(source) }
            1 -> player.play()
            2 -> player.pause()
            3 -> player.stop()
            4 -> player.seekTo(op.arg)
            5 -> player.seekForward(op.arg)
            6 -> player.seekBackward(op.arg)
            7 -> player.setPlaybackSpeed(0.1f + op.arg % 40 / 10f)
            8 -> player.setVolume(op.arg % 12 / 10f - 0.1f)
            9 -> player.setMuted(op.arg % 2L == 0L)
            10 -> player.setRepeatMode(if (op.arg % 2L == 0L) RepeatMode.One else RepeatMode.Off)
            11 -> player.unload()
            12 -> { engine.position = op.arg; scheduler.advanceTimeBy(150L); scheduler.runCurrent() }
            13 -> engine.sink?.onCompleted()
            14 -> engine.sink?.onBufferingChanged(op.arg % 2L == 0L)
            15 -> engine.sink?.onVideoSizeChanged(if (op.arg % 3L == 0L) null else VideoSize(100 + (op.arg % 50).toInt(), 80))
            16 -> engine.sink?.onFailed(IOException("late"))
            else -> player.replay()
        }
    }

    private fun run(seed: Long, withNeighbour: Boolean, releaseNeighbourAt: Int, neighbourFails: Boolean): Side {
        val a = Side("a")
        val b: Side? = if (withNeighbour) Side("b") else null
        val rndA = Random(seed)
        val rndB = Random(seed * 31 + 7)
        val opsA: List<Op> = ops(rndA, 60)
        val opsB: List<Op> = ops(rndB, 60)
        for (i in opsA.indices) {
            a.apply(opsA[i], source)
            if (b != null) {
                if (i == releaseNeighbourAt) b.player.release()
                if (neighbourFails && i % 7 == 3) b.engine.sink?.onFailed(IOException("b dies"))
                b.apply(opsB[i], source)
                b.apply(opsB[(i * 5 + 3) % opsB.size], source)
            }
        }
        val a2: Side = a
        b?.close()
        // Fail-safe: nothing escaped on either side.
        assertEquals(emptyList(), a2.escaped, "seed $seed")
        return a2
    }

    @Test
    fun `what a player shows and tells its engine does not depend on a neighbour that runs is released or fails`() {
        val base: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 14_014L
        val kinds: MutableSet<String> = mutableSetOf()
        for (k in 0 until 80) {
            val seed: Long = base + k
            val solo: Side = run(seed, withNeighbour = false, releaseNeighbourAt = -1, neighbourFails = false)
            val soloHistory: List<String> = solo.history.toList()
            val soloLog: List<String> = solo.engine.log.toList()
            solo.close()
            for (entry in soloHistory) if (entry.startsWith("state=")) kinds += entry.removePrefix("state=").substringBefore('(')
            for ((release, fails) in listOf((-1) to false, 20 to false, 35 to true, 0 to true)) {
                val paired: Side = run(seed, withNeighbour = true, releaseNeighbourAt = release, neighbourFails = fails)
                assertEquals(soloHistory, paired.history, "seed $seed neighbour release at $release fails $fails: what A showed")
                assertEquals(soloLog, paired.engine.log, "seed $seed neighbour release at $release fails $fails: what A's engine heard")
                paired.close()
            }
        }
        // Not vacuous: the sequences reach every state.
        for (kind in listOf("Idle", "Preparing", "Ready", "Playing", "Paused", "Completed", "Error")) {
            assertTrue(kind in kinds, "no sequence reached $kind, reached $kinds")
        }
    }

    @Test
    fun `a failure reported on one engine and a release of one player leave the other playing and polling`() {
        val a = Side("a")
        val b = Side("b")
        runBlocking { a.player.prepare(source); b.player.prepare(source) }
        a.player.play()
        b.player.play()
        b.engine.position = 2_000L
        a.engine.position = 1_000L
        a.scheduler.advanceTimeBy(150L); a.scheduler.runCurrent()
        b.scheduler.advanceTimeBy(150L); b.scheduler.runCurrent()
        b.engine.sink!!.onFailed(IOException("b only"))
        assertTrue(b.player.stateFlow.value is VideoPlayerState.Error)
        assertTrue(a.player.stateFlow.value is VideoPlayerState.Playing, "A state ${a.player.stateFlow.value}")
        b.player.release()
        assertEquals(1, b.engine.log.count { it == "dispose" })
        assertEquals(0, a.engine.log.count { it == "dispose" }, "releasing B disposed A's engine")
        a.engine.position = 3_000L
        a.scheduler.advanceTimeBy(150L); a.scheduler.runCurrent()
        assertEquals(3_000L, a.player.playbackPositionFlow.value, "A stopped polling when B was released")
        // A settings change on A does not reach B, nor B's engine.
        val bVolumes: Int = b.engine.volumes.size
        a.player.setVolume(0.2f)
        assertEquals(bVolumes, b.engine.volumes.size)
        assertEquals(1f, b.player.volumeFlow.value)
        a.close()
        assertEquals(1, a.engine.log.count { it == "dispose" })
        assertEquals(emptyList(), a.engine.callsAfterDispose() + b.engine.callsAfterDispose())
    }

    @Test
    fun `a player created after another was released starts from a clean state and the released one stays inert`() {
        val first = Side("first")
        runBlocking { first.player.prepare(source) }
        first.player.setPlaybackSpeed(2f)
        first.player.setVolume(0.1f)
        first.player.setMuted(true)
        first.player.setRepeatMode(RepeatMode.One)
        first.player.play()
        first.player.release()
        val second = Side("second")
        assertEquals(VideoPlayerState.Idle, second.player.stateFlow.value)
        assertEquals(1f, second.player.playbackSpeedFlow.value)
        assertEquals(1f, second.player.volumeFlow.value)
        assertEquals(false, second.player.isMutedFlow.value)
        assertEquals(RepeatMode.Off, second.player.repeatModeFlow.value)
        runBlocking { second.player.prepare(source) }
        second.player.play()
        first.player.play()
        first.player.setVolume(0.7f)
        assertTrue(second.player.stateFlow.value is VideoPlayerState.Playing)
        assertEquals(1f, second.player.volumeFlow.value)
        assertEquals(VideoPlayerState.Idle, first.player.stateFlow.value)
        first.close()
        second.close()
    }
}
