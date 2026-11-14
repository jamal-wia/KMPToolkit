package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D1 round 13, Q4 (N1 of the r12 review): a `prepare` whose start step is cut by a `prepare` a collector of one of the reset
 * flows called from inside it (the deeper one waits for the outer to finish) leaves `stateFlow` on the state it left while the
 * engine no longer holds a source, until the deeper `prepare` begins. Engine reports and calls a collector makes in that gap
 * are taken for a player that still has its source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NestedPrepareCutWindowD1R13Test {

    private val other: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @Test
    fun `a play made after a prepare was cut by a deeper prepare does not start an engine that holds no source`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow() // Ready, buffered position 4000
            var nested: Job? = null
            // The collector of the buffered position (reset by the start step) calls the deeper prepare, then the engine reports a size.
            rig.reactOnce(rig.player.bufferedPositionFlow) {
                nested = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }
                checkNotNull(rig.engine.sink).onVideoSizeChanged(VideoSize(2, 2))
            }
            // The collector of the picture size plays: what a player sees right after prepare() was called is Preparing.
            val playRequested: InlineRigD1R11.Fired = rig.reactOnce(rig.player.videoSizeFlow) { rig.player.play() }
            val states: List<VideoPlayerState> = rig.recordStates()
            val logBefore: Int = rig.engine.log.size

            val outer: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(rig.source) }
            runBlocking { withTimeout(10_000L) { outer.join(); checkNotNull(nested).join() } }

            val calls: List<String> = rig.engine.log.drop(logBefore)
            val releaseAt: Int = calls.indexOf("release")
            val loadAt: Int = calls.indexOf("load")
            assertTrue(playRequested.value, "the size collector ran its play: $calls")
            assertTrue(releaseAt in 0 until loadAt, "the first prepare freed the engine before the deeper one loaded: $calls")
            assertEquals(
                emptyList(),
                calls.subList(releaseAt, loadAt).filter { it == "start" || it == "seek" || it == "pause" },
                "no transport call reaches the engine between its release and the next load: $calls",
            )
            assertTrue(states.none { it is VideoPlayerState.Playing }, "no Playing is published for a player whose source was dropped by prepare: $states")
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value)
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }

    /**
     * The guard of the placeholder branch: a prepare whose reset a collector cut with an `unload` ends Idle, and the cut
     * step publishes no Preparing over it (an unload has published Idle itself; its placeholder load is cancelled).
     */
    @Test
    fun `an unload called from a collector inside the reset of a prepare ends Idle without a Preparing published by the cut step`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow() // Ready, buffered position 4000
            val unloadRequested: InlineRigD1R11.Fired = rig.reactOnce(rig.player.bufferedPositionFlow) { rig.player.unload() }
            val states: List<VideoPlayerState> = rig.recordStates()
            val logBefore: Int = rig.engine.log.size

            val outer: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(rig.source) }
            runBlocking { withTimeout(10_000L) { outer.join() } }

            val calls: List<String> = rig.engine.log.drop(logBefore)
            assertTrue(unloadRequested.value, "the buffered position collector ran its unload: $calls")
            assertTrue(calls.none { it == "load" }, "the cut prepare never loaded: $calls")
            assertTrue(states.none { it is VideoPlayerState.Preparing }, "no Preparing is published over an unloaded player: $states")
            assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }
}
