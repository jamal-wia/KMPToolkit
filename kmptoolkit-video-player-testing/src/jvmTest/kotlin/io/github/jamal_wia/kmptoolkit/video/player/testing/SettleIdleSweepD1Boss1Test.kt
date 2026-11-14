package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D1 boss round 1: the settle-path sweep. Every path that settles the player on `Idle` after the engine's source was
 * dropped (`unload`, `release`, the cancel path of `prepare` before its start step and in flight) is run under a
 * collector of each flow the reset writes, reacting with each player call a collector can make (`04-api-reference.md`:
 * such a call acts on the state the running transition is establishing, so it does nothing while a call settles on
 * `Idle`; a `prepare` it starts owns the state from then on). Oracle per cell:
 * - the promised end: `Idle`, or the state of the `prepare` the collector called (`Ready`; after `release` that call is
 *   refused, which ends `Idle` unless the collector of the state itself made it, then `Error(released)`);
 * - the states published on the way: no `Idle` between a source flow's reset and the `Preparing` of the call the
 *   collector made, and nothing of the old source after the first `Idle` or `Preparing`;
 * - `Playing` if and only if the engine was started if and only if the poll is alive;
 * - no engine call after the engine's last `release` except the load the collector's `prepare` made (and the settings
 *   it pushes), and the `dispose` of `release`;
 * - the four source flows initial (the buffered position the engine reports for the new source, when one loaded).
 * Guard: every cell's collector fired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettleIdleSweepD1Boss1Test {

    private enum class Path { UNLOAD, RELEASE, CANCEL_BEFORE_START, CANCEL_IN_FLIGHT }

    private enum class Watched { POSITION, BUFFERED, BUFFERING, SIZE, STATE }

    private enum class Reaction { PLAY, PAUSE, SEEK, STOP, MUTE, PREPARE, UNLOAD }

    private val next: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val other: VideoSource = VideoSource.Remote("https://example.test/c.mp4")

    /** In flight, a source flow changes only when the engine reports a size while preparing, so only that flow and the state are written. */
    private fun watchedFor(path: Path): List<Watched> =
        if (path == Path.CANCEL_IN_FLIGHT) listOf(Watched.SIZE, Watched.STATE) else Watched.values().toList()

    private fun flowOf(rig: InlineRigD1R11, watched: Watched): Flow<*> = when (watched) {
        Watched.POSITION -> rig.player.playbackPositionFlow
        Watched.BUFFERED -> rig.player.bufferedPositionFlow
        Watched.BUFFERING -> rig.player.isBufferingFlow
        Watched.SIZE -> rig.player.videoSizeFlow
        Watched.STATE -> rig.player.stateFlow
    }

    private fun react(rig: InlineRigD1R11, reaction: Reaction) {
        when (reaction) {
            Reaction.PLAY -> rig.player.play()
            Reaction.PAUSE -> rig.player.pause()
            Reaction.SEEK -> rig.player.seekTo(5_000L)
            Reaction.STOP -> rig.player.stop()
            Reaction.MUTE -> rig.player.setMuted(true)
            Reaction.PREPARE -> {
                rig.engine.loadAction = {}
                rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }
            }
            Reaction.UNLOAD -> rig.player.unload()
        }
    }

    private fun pollAlive(rig: InlineRigD1R11): Boolean {
        val field = rig.player.javaClass.getDeclaredField("positionJob").apply { isAccessible = true }
        val job: Job? = field.get(rig.player) as Job?
        return job != null && job.isActive
    }

    private fun names(states: List<VideoPlayerState>): List<String> = states.map { it::class.simpleName.orEmpty() }

    /** Runs one cell and returns its failures. */
    private fun cell(path: Path, watched: Watched, reaction: Reaction): List<String> {
        val rig = InlineRigD1R11()
        val failures: MutableList<String> = mutableListOf()
        val label = "$path / collector of $watched / $reaction"
        try {
            val old: String
            val trigger: () -> Unit
            when (path) {
                Path.CANCEL_IN_FLIGHT -> {
                    rig.prepareNow()
                    rig.engine.loadAction = { awaitCancellation() }
                    val caller: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            rig.player.prepare(next)
                        } catch (expected: CancellationException) {
                            // The caller is cancelled below.
                        }
                    }
                    checkNotNull(rig.engine.sink).onVideoSizeChanged(VideoSize(640, 360))
                    old = "Preparing"
                    trigger = { runBlocking { withTimeout(10_000L) { caller.cancelAndJoin() } } }
                }
                else -> {
                    rig.playingStalledWithSize()
                    old = "Playing"
                    trigger = when (path) {
                        Path.UNLOAD -> { { rig.player.unload() } }
                        Path.RELEASE -> { { rig.player.release() } }
                        else -> {
                            {
                                runBlocking {
                                    withTimeout(10_000L) {
                                        launch {
                                            coroutineContext[Job]!!.cancel()
                                            try {
                                                rig.player.prepare(next)
                                            } catch (expected: CancellationException) {
                                                // The caller was cancelled before the call.
                                            }
                                        }.join()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val states: List<VideoPlayerState> = rig.recordStates()
            val fired: InlineRigD1R11.Fired = rig.reactOnce(flowOf(rig, watched)) { react(rig, reaction) }
            trigger()

            if (!fired.value) failures += "$label: the collector never fired (guard)"
            val calledPrepare: Boolean = reaction == Reaction.PREPARE
            val state: VideoPlayerState = rig.player.stateFlow.value
            val loadedNew: Boolean = calledPrepare && path != Path.RELEASE
            val refusedByState: Boolean = path == Path.RELEASE && calledPrepare && watched == Watched.STATE
            // The promised end.
            val endName: String = when {
                loadedNew -> "Ready"
                refusedByState -> "Error"
                else -> "Idle"
            }
            if (names(listOf(state)).single() != endName) failures += "$label: ends $state, expected $endName; states $states"
            // The way there.
            if (path != Path.RELEASE) {
                val expected: MutableList<String> = mutableListOf(old)
                val idleFirst: Boolean = watched == Watched.STATE || !calledPrepare
                if (idleFirst) expected += "Idle"
                if (calledPrepare) expected += "Ready"
                // Preparing is left out of the comparison: a collector on an immediate dispatcher that is resumed after a
                // nested prepare has already gone on to Ready sees only the latest value (StateFlow conflates), so whether
                // it records the Preparing between is not part of the contract. The Idle between a source flow's reset and
                // the call the collector made is: it would show the old source's end where the new call owns the state.
                val seen: List<String> = names(states).filter { it != "Preparing" }
                val wanted: List<String> = expected.filter { it != "Preparing" }
                if (seen != wanted) failures += "$label: states ${names(states)}, expected $wanted (Preparing aside)"
            } else if (names(states).drop(1).any { it == "Playing" || it == "Ready" || it == "Preparing" || it == "Paused" }) {
                failures += "$label: a state of the old or a new source after release: ${names(states)}"
            }
            // Playing <=> started <=> polling.
            val playing: Boolean = state is VideoPlayerState.Playing
            if (rig.engine.playing != playing || pollAlive(rig) != playing) {
                failures += "$label: state $state, engine started ${rig.engine.playing}, poll alive ${pollAlive(rig)}"
            }
            // Nothing reaches the engine after its last release but the new load and the dispose.
            val lastRelease: Int = rig.engine.log.lastIndexOf("release")
            if (lastRelease < 0) failures += "$label: the engine was never released"
            val afterRelease: List<String> = rig.engine.log.drop(lastRelease + 1)
            val allowed: Set<String> = when {
                path == Path.RELEASE -> setOf("dispose")
                loadedNew -> setOf("load", "volume", "looping", "speed")
                else -> emptySet()
            }
            if (!allowed.containsAll(afterRelease)) failures += "$label: engine calls after its release: $afterRelease"
            // The source flows.
            val buffered: Long = if (loadedNew) rig.engine.buffered else 0L
            if (rig.player.playbackPositionFlow.value != 0L || rig.player.bufferedPositionFlow.value != buffered ||
                rig.player.isBufferingFlow.value || rig.player.videoSizeFlow.value != null
            ) {
                failures += "$label: source flows ${rig.player.playbackPositionFlow.value}, ${rig.player.bufferedPositionFlow.value}, " +
                    "${rig.player.isBufferingFlow.value}, ${rig.player.videoSizeFlow.value}"
            }
            if (rig.escaped.isNotEmpty()) failures += "$label: escaped ${rig.escaped}"
        } finally {
            rig.close()
        }
        return failures
    }

    private fun sweep(path: Path) {
        val failures: MutableList<String> = mutableListOf()
        for (watched: Watched in watchedFor(path)) {
            for (reaction: Reaction in Reaction.values()) failures += cell(path, watched, reaction)
        }
        assertTrue(failures.isEmpty(), "${failures.size} failing cells:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `unload settles on Idle under every collector and reaction`() = sweep(Path.UNLOAD)

    @Test
    fun `release settles on Idle under every collector and reaction`() = sweep(Path.RELEASE)

    @Test
    fun `the cancel path before the start step settles on Idle under every collector and reaction`() =
        sweep(Path.CANCEL_BEFORE_START)

    @Test
    fun `the cancel path in flight settles on Idle under every collector and reaction`() = sweep(Path.CANCEL_IN_FLIGHT)
}
