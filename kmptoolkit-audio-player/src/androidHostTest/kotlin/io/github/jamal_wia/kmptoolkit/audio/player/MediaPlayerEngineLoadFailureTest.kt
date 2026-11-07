package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer

/**
 * Resource starvation through failing sources: empty and odd paths, a missing asset, a string that
 * is no URL. Each load fails inside the platform call, after the `MediaPlayer` was created, and none
 * of them may leave a native player behind, however many fail in a row.
 */
@RunWith(AndroidJUnit4::class)
class MediaPlayerEngineLoadFailureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val created: MutableList<MediaPlayer> = Collections.synchronizedList(mutableListOf())

    @Before
    fun trackPlayers() {
        ShadowMediaPlayer.setCreateListener { player: MediaPlayer, _: ShadowMediaPlayer -> created += player }
    }

    @After
    fun stopTracking() {
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun `sources that fail to load leave no media player unreleased`() = runTest {
        val engine = MediaPlayerEngine(context)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), Dispatchers.Default)
        try {
            val sources: List<AudioSource> = listOf(
                AudioSource.Asset(""),
                AudioSource.Asset("missing/none.mp3"),
                AudioSource.Asset("/"),
                AudioSource.File(""),
                AudioSource.File("/"),
                AudioSource.File("/nonexistent/none.mp3"),
                AudioSource.File("relative/none.mp3"),
                AudioSource.File("/tmp/\u0000nul.mp3"),
                AudioSource.Remote(""),
                AudioSource.Remote("not a url"),
                AudioSource.Remote("ftp://example.test/none.mp3"),
            )
            repeat(8) { round: Int ->
                for (source in sources) {
                    player.prepare(source)
                    val state = player.stateFlow.value
                    assertTrue(state is PlayerState.Error || state is PlayerState.Ready, "round $round $source gave $state")
                    shadowOf(Looper.getMainLooper()).idle()
                }
            }
            player.unload()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            val leaked: List<MediaPlayer> = created.filter { shadowOf(it).state != ShadowMediaPlayer.State.END }
            assertEquals(0, leaked.size, "unreleased media players after ${created.size} loads: ${leaked.map { shadowOf(it).state }}")
        } finally {
            player.release()
        }
        assertIs<PlayerState>(player.stateFlow.value)
    }
}
