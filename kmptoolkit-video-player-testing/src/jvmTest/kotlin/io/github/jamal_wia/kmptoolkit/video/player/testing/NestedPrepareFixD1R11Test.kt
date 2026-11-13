package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D1 round 11 fix2 (S1 and the effective Ready). An `unload()` leaves the state to a `prepare` that a collector called from
 * inside its reset (the prepare owns the state, `unload` must not publish `Idle` over its `Preparing`); and a buffering
 * collector that plays from inside the last step of `prepare` sees `Ready`, so the play is not a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NestedPrepareFixD1R11Test {

    private val other: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @Test
    fun `a prepare called by a position collector inside the reset of unload owns the state and the load reaches Ready`() {
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            val loading: CompletableDeferred<Unit> = CompletableDeferred()
            rig.engine.loadAction = { loading.await() }
            var nested: Job? = null
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) {
                nested = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(other) }
            }
            val seen: MutableList<VideoPlayerState> = mutableListOf()
            val recording: List<VideoPlayerState> = rig.recordStates()
            val before: Int = recording.size
            rig.player.unload()
            seen += recording.drop(before)
            assertTrue(fired.value, "the collector called prepare from inside the reset")
            assertEquals(VideoPlayerState.Preparing, rig.player.stateFlow.value, "the nested prepare owns the state, unload did not publish Idle over it")
            assertTrue(VideoPlayerState.Idle !in seen, "no Idle between: $seen")
            val logAfterUnload: Int = rig.engine.log.size
            loading.complete(Unit)
            runBlocking { withTimeout(10_000L) { checkNotNull(nested).join() } }
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "the second source is loaded")
            assertEquals(emptyList(), rig.engine.log.drop(logAfterUnload).filter { it == "release" || it == "dispose" }, "the engine holds the second source")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.engine.loadAction = {}
            rig.close()
        }
    }

    @Test
    fun `a buffering collector that plays inside the last step of prepare sees Ready and the player ends Playing with the engine started`() {
        val rig = InlineRigD1R11()
        try {
            // The source reports a stall while it loads, so the last step publishes isBuffering = true after the buffered position.
            rig.engine.loadAction = { checkNotNull(rig.engine.sink).onBufferingChanged(true) }
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.isBufferingFlow) { rig.player.play() }
            rig.prepareNow()
            assertTrue(fired.value, "the buffering collector ran inside the last step")
            assertEquals(VideoPlayerState.Playing(10_000L, 0L), rig.player.stateFlow.value, "04: the call stands")
            assertEquals(true, rig.engine.playing, "the engine is started")
            assertEquals(true, rig.player.isBufferingFlow.value)
        } finally {
            rig.close()
        }
    }
}
