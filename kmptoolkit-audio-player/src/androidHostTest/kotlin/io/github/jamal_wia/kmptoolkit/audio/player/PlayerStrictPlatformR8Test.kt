package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** A `MediaPlayer` whose playback parameters can be told to refuse, as a device can. */
@Implements(MediaPlayer::class)
class RefusingParamsShadowMediaPlayer : ShadowMediaPlayer() {

    @Implementation
    fun getPlaybackParams(): PlaybackParams = PlaybackParams()

    @Implementation
    fun setPlaybackParams(params: PlaybackParams) {
        refusal.get()?.let { throw it }
    }

    companion object {
        val refusal: AtomicReference<RuntimeException?> = AtomicReference(null)
    }
}

/**
 * The factory-built player (real [MediaPlayerEngine]) over Robolectric's `MediaPlayer` state machine
 * at every API level the host knows, 24 to 35: load failures of every source kind, platform errors
 * in each state, a platform stuck in the error state that never told anybody, extreme lengths,
 * a device that refuses a rate, and two players at once. Every `MediaPlayer` the engine ever created
 * must end up released. A real `MediaPlayer` cannot run on the JVM; this is the strictest stand-in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 29, 30, 31, 33, 34, 35], shadows = [RefusingParamsShadowMediaPlayer::class])
class PlayerStrictPlatformR8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created: CopyOnWriteArrayList<Pair<MediaPlayer, ShadowMediaPlayer>> = CopyOnWriteArrayList()
    private val players: MutableList<AudioPlayer> = mutableListOf()
    private var counter: Int = 0

    @Before
    fun setUp() {
        RefusingParamsShadowMediaPlayer.refusal.set(null)
        ShadowMediaPlayer.setCreateListener { player: MediaPlayer, shadow: ShadowMediaPlayer ->
            created += player to shadow
        }
    }

    @After
    fun tearDown() {
        players.forEach { it.release() }
        shadowOf(Looper.getMainLooper()).idle()
        ShadowMediaPlayer.setCreateListener(null)
        RefusingParamsShadowMediaPlayer.refusal.set(null)
    }

    private fun newPlayer(): AudioPlayer =
        createAudioPlayer(context, AudioPlayerConfig(), Dispatchers.Default).also { players += it }

    private fun url(durationMs: Int): String {
        val value = "https://example.test/r8-${counter++}-${System.nanoTime()}.mp3"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(value), ShadowMediaPlayer.MediaInfo(durationMs, 0))
        return value
    }

    private fun <T> blocking(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(60_000) { block() } }

    private suspend fun pumpUntil(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 15.seconds
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            withContext(Dispatchers.Default) { delay(1L) }
        }
    }

    private suspend fun CoroutineScope.load(player: AudioPlayer, source: AudioSource, until: (PlayerState) -> Boolean) {
        val loading = launch(Dispatchers.Default) { player.prepare(source) }
        pumpUntil("settled after prepare of $source") { until(player.stateFlow.value) }
        pumpUntil("prepare returns") { loading.isCompleted }
    }

    private fun ready(player: AudioPlayer, durationMs: Int = 60_000) {
        blocking { load(player, AudioSource.Remote(url(durationMs))) { it is PlayerState.Ready } }
    }

    private fun assertNoLeak(label: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val open: List<Int> = created.mapIndexedNotNull { index: Int, (_, shadow: ShadowMediaPlayer) ->
            if (shadow.state != ShadowMediaPlayer.State.END) index else null
        }
        assertTrue(open.isEmpty(), "$label: media players $open of ${created.size} were never released")
    }

    @Test
    fun `a source that will not load ends in Error whatever failed and leaves no media player held`() {
        val ioFailing = "https://example.test/r8-io-${System.nanoTime()}.mp3"
        ShadowMediaPlayer.addException(DataSource.toDataSource(ioFailing), IOException("no route"))
        val runtimeFailing = "https://example.test/r8-rt-${System.nanoTime()}.mp3"
        ShadowMediaPlayer.addException(DataSource.toDataSource(runtimeFailing), IllegalStateException("source refused"))
        val sources: List<AudioSource> = listOf(
            AudioSource.Remote(ioFailing),
            AudioSource.Remote(runtimeFailing),
            AudioSource.Asset("missing/r8-${System.nanoTime()}.mp3"),
            AudioSource.File("   "),
        )
        val player: AudioPlayer = newPlayer()
        for (source in sources) {
            blocking { load(player, source) { it is PlayerState.Error } }
            val error: PlayerState.Error = assertIs<PlayerState.Error>(player.stateFlow.value, "$source")
            assertNotNull(error.cause)
            if (source is AudioSource.Asset) assertTrue(error.cause is FileNotFoundException || error.cause is IOException, "${error.cause}")
            assertNoLeak("$source")
        }
        // the same player recovers with a good source
        ready(player, 5_000)
        assertEquals(PlayerState.Ready(5_000L), player.stateFlow.value)
    }

    @Test
    fun `a platform error in Ready Playing or Paused ends in Error frees the handle and the player can be reused`() {
        for (phase in listOf("ready", "playing", "paused")) {
            val player: AudioPlayer = newPlayer()
            ready(player)
            val shadow: ShadowMediaPlayer = created.last().second
            if (phase != "ready") player.play()
            if (phase == "paused") player.pause()
            shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0)
            blocking { pumpUntil("$phase: Error") { player.stateFlow.value is PlayerState.Error } }
            player.play()
            player.pause()
            player.seekTo(1_000)
            assertIs<PlayerState.Error>(player.stateFlow.value, "$phase: an operation revived an errored player")
            player.stop()
            ready(player, 8_000)
            assertEquals(PlayerState.Ready(8_000L), player.stateFlow.value, phase)
            player.release()
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertNoLeak(phase)
        }
    }

    @Test
    fun `completion then replay plays again and a platform stuck in the error state breaks no call`() {
        val player: AudioPlayer = newPlayer()
        ready(player, 20_000)
        val shadow: ShadowMediaPlayer = created.last().second
        player.play()
        shadow.invokeCompletionListener()
        blocking { pumpUntil("Completed") { player.stateFlow.value is PlayerState.Completed } }
        player.replay()
        assertIs<PlayerState.Playing>(player.stateFlow.value)

        // The platform drops into ERROR without ever telling anybody, and every call now throws ISE.
        shadow.state = ShadowMediaPlayer.State.ERROR
        player.setPlaybackSpeed(2f)
        player.pause()
        player.play()
        player.seekTo(5_000)
        player.seekForward(1_000)
        player.seekBackward(1_000)
        player.stop()
        player.replay()
        blocking { delay(150) } // the position poll runs against the broken handle
        player.unload()
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        ready(player, 9_000)
        assertEquals(PlayerState.Ready(9_000L), player.stateFlow.value)
        player.release()
        assertNoLeak("stuck platform")
    }

    @Test
    fun `zero and enormous lengths and saturating seeks never break the player`() {
        val player: AudioPlayer = newPlayer()
        ready(player, 0)
        assertEquals(PlayerState.Ready(0L), player.stateFlow.value)
        player.seekForward(Long.MAX_VALUE)
        player.seekBackward(Long.MAX_VALUE)
        player.seekTo(Long.MIN_VALUE)
        player.seekTo(Long.MAX_VALUE)

        ready(player, Int.MAX_VALUE)
        assertEquals(PlayerState.Ready(Int.MAX_VALUE.toLong()), player.stateFlow.value)
        player.play()
        player.seekForward(Long.MAX_VALUE)
        player.seekBackward(Long.MAX_VALUE)
        player.seekTo(Long.MAX_VALUE)
        player.seekTo(-1)
        blocking { delay(100) }
        assertTrue(player.stateFlow.value !is PlayerState.Error, "state ${player.stateFlow.value}")
        player.release()
        assertNoLeak("lengths")
    }

    @Test
    fun `a device that refuses a rate keeps playing and still reports the requested rate`() {
        val player: AudioPlayer = newPlayer()
        ready(player)
        player.play()
        for (refusal in listOf<RuntimeException>(IllegalArgumentException("rate"), IllegalStateException("state"))) {
            RefusingParamsShadowMediaPlayer.refusal.set(refusal)
            player.setPlaybackSpeed(2f)
            assertIs<PlayerState.Playing>(player.stateFlow.value, "$refusal")
            assertEquals(2f, player.playbackSpeed)
            player.pause()
            player.play()
            assertIs<PlayerState.Playing>(player.stateFlow.value)
        }
        player.release()
        assertNoLeak("refused rate")
    }

    @Test
    fun `two players at once are independent and releasing one leaves the other playing`() {
        val first: AudioPlayer = newPlayer()
        val second: AudioPlayer = newPlayer()
        ready(first, 30_000)
        val firstShadow: ShadowMediaPlayer = created.last().second
        ready(second, 40_000)
        first.play()
        second.play()
        second.pause()
        assertIs<PlayerState.Playing>(first.stateFlow.value)
        assertIs<PlayerState.Paused>(second.stateFlow.value)
        firstShadow.invokeErrorListener(1, 1)
        blocking { pumpUntil("first errors") { first.stateFlow.value is PlayerState.Error } }
        assertIs<PlayerState.Paused>(second.stateFlow.value, "an error of one player reached the other")
        first.release()
        second.play()
        assertIs<PlayerState.Playing>(second.stateFlow.value)
        second.release()
        assertNoLeak("two players")
    }
}
