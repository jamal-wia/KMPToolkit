package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The guide's sentence about a prepare against a host that never answers: it waits until it is
 * cancelled by a newer prepare, unload, release, or by cancelling the calling coroutine, and
 * whichever way it ends, its native player is freed. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjDocumentedBehaviourAdversarialTest {

    private val runtime = FakeVlcRuntime().also { it.parseResult = null }

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newPlayer(): VideoPlayer = createVlcjVideoPlayer(
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime }),
        VideoPlayerConfig(),
        Dispatchers.Default,
    )

    private suspend fun awaitPlayers(count: Int) = withTimeout(5_000L) {
        while (runtime.players.size < count) delay(10L)
    }

    private suspend fun awaitFreed(index: Int) = withTimeout(5_000L) {
        while (runtime.players[index].calls.count { it == "release" } < 1) delay(10L)
    }

    @Test
    fun `a prepare that never gets an answer ends with unload and frees its native player`() = runBlocking<Unit> {
        val player: VideoPlayer = newPlayer()
        val prepare: Job = launch(Dispatchers.Default) { runCatching { player.prepare(VideoSource.File(TestClip.file.path)) } }
        awaitPlayers(1)
        player.unload()
        withTimeout(5_000L) { prepare.join() }
        awaitFreed(0)
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a prepare that never gets an answer ends with a newer prepare and frees its native player`() = runBlocking<Unit> {
        val player: VideoPlayer = newPlayer()
        val first: Job = launch(Dispatchers.Default) { runCatching { player.prepare(VideoSource.File(TestClip.file.path)) } }
        awaitPlayers(1)
        val second: Job = launch(Dispatchers.Default) { runCatching { player.prepare(VideoSource.File(TestClip.file.path)) } }
        awaitPlayers(2)
        withTimeout(5_000L) { first.join() }
        awaitFreed(0)
        runtime.players[1].fire { parsed(VlcParseStatus.DONE) }
        withTimeout(5_000L) { second.join() }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
        assertEquals(0, runtime.players[1].calls.count { it == "release" }, "the second player was freed with the first")
        player.release()
    }

    @Test
    fun `a prepare that never gets an answer ends when its calling coroutine is cancelled and frees its native player`() = runBlocking<Unit> {
        val player: VideoPlayer = newPlayer()
        val prepare: Job = launch(Dispatchers.Default) { player.prepare(VideoSource.File(TestClip.file.path)) }
        awaitPlayers(1)
        withTimeout(5_000L) { prepare.cancelAndJoin() }
        awaitFreed(0)
        player.release()
    }

    @Test
    fun `a prepare that never gets an answer ends with release and the player and libvlc are freed once`() = runBlocking<Unit> {
        val player: VideoPlayer = newPlayer()
        val prepare: Job = launch(Dispatchers.Default) { runCatching { player.prepare(VideoSource.File(TestClip.file.path)) } }
        awaitPlayers(1)
        player.release()
        withTimeout(5_000L) { prepare.join() }
        awaitFreed(0)
        withTimeout(5_000L) { while (runtime.released < 1) delay(10L) }
        assertEquals(1, runtime.released)
        assertEquals(1, runtime.players[0].calls.count { it == "release" })
    }
}
