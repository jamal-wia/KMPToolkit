package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Round 12, Q1 (player half): hostile sources through the factory-built player (the real `MediaPlayerEngine`) over
 * Robolectric's `MediaPlayer` state machine. A path or URL of ten thousand characters, a NUL, a directory, a device,
 * a traversing asset name: `prepare` returns within a bound and never throws, the state is a typed `Error` (the
 * platform's own exception) or `Ready`, transport calls on an `Error` do not revive it, the same player then plays an
 * ordinary source, and every `MediaPlayer` the engine created ends up released.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlayerHostileSourcesR12Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created: CopyOnWriteArrayList<ShadowMediaPlayer> = CopyOnWriteArrayList()
    private lateinit var directory: File

    @Before
    fun setUp() {
        ShadowMediaPlayer.setCreateListener { _: MediaPlayer, shadow: ShadowMediaPlayer -> created += shadow }
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "r12src-").toFile()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        ShadowMediaPlayer.setCreateListener(null)
        directory.deleteRecursively()
    }

    private suspend fun pump(what: String, condition: () -> Boolean) {
        // timing-hygiene-ignore: TH2 bound hang deadline of a wait for the platform looper, never asserted as a time
        val deadline = TimeSource.Monotonic.markNow() + 15.seconds
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            // timing-hygiene-ignore: TH1 inject yields to the looper while polling a bounded condition
            withContext(Dispatchers.Default) { delay(1L) }
        }
    }

    @Test
    fun `hostile sources end typed and the player plays an ordinary source afterwards with nothing leaked`() {
        val sources: Map<String, AudioSource> = mapOf(
            "file of ten thousand characters" to AudioSource.File(File(directory, "f".repeat(10_000)).path),
            "file with a NUL" to AudioSource.File(File(directory, "a\u0000b.m4a").path),
            "a directory as the file" to AudioSource.File(directory.path),
            "a device as the file" to AudioSource.File("/dev/null"),
            "file with combining marks" to AudioSource.File(File(directory, "ẹ́.m4a").path),
            "remote of ten thousand characters" to AudioSource.Remote("https://example.test/" + "r".repeat(10_000)),
            "remote with a NUL" to AudioSource.Remote("https://example.test/a\u0000b.mp3"),
            "asset of ten thousand characters" to AudioSource.Asset("a".repeat(10_000)),
            "asset that climbs out" to AudioSource.Asset("../../../etc/hosts"),
            "empty asset" to AudioSource.Asset(""),
        )
        val ok = "https://example.test/r12-ok.mp3"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(ok), ShadowMediaPlayer.MediaInfo(5_000, 0))
        val player: AudioPlayer = createAudioPlayer(context, AudioPlayerConfig(), Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(120_000) {
                    repeat(3) { round: Int ->
                        for ((label: String, source: AudioSource) in sources) {
                            val loading = launch(Dispatchers.Default) { player.prepare(source) }
                            pump("$label settles") { loading.isCompleted }
                            val state: PlayerState = player.stateFlow.value
                            assertTrue(state is PlayerState.Error || state is PlayerState.Ready, "round $round $label gave $state")
                            if (state is PlayerState.Error) {
                                player.play()
                                player.seekTo(Long.MAX_VALUE)
                                player.seekForward(Long.MIN_VALUE)
                                player.replay()
                                assertIs<PlayerState.Error>(player.stateFlow.value, "round $round $label: a transport call revived an Error")
                            }
                        }
                    }
                    val loading = launch(Dispatchers.Default) { player.prepare(AudioSource.Remote(ok)) }
                    pump("ordinary source ready") { player.stateFlow.value is PlayerState.Ready }
                    pump("ordinary prepare returns") { loading.isCompleted }
                    assertEquals(PlayerState.Ready(5_000L), player.stateFlow.value)
                }
            }
        } finally {
            player.release()
        }
        shadowOf(Looper.getMainLooper()).idle()
        val open: List<Int> = created.mapIndexedNotNull { index: Int, shadow: ShadowMediaPlayer ->
            if (shadow.state != ShadowMediaPlayer.State.END) index else null
        }
        assertTrue(open.isEmpty(), "media players $open of ${created.size} were never released")
    }

    @Test
    fun `a live stream whose platform length is minus one is a known unknown and never a negative length`() {
        val live = "https://example.test/r12-live.mp3"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(live), ShadowMediaPlayer.MediaInfo(-1, 0))
        val player: AudioPlayer = createAudioPlayer(context, AudioPlayerConfig(), Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(60_000) {
                    val loading = launch(Dispatchers.Default) { player.prepare(AudioSource.Remote(live)) }
                    pump("live source ready") { player.stateFlow.value is PlayerState.Ready }
                    pump("live prepare returns") { loading.isCompleted }
                    assertEquals(PlayerState.Ready(0L), player.stateFlow.value, "a negative platform length must read as unknown (0)")
                }
            }
        } finally {
            player.release()
        }
    }
}
