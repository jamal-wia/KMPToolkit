package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Storm through the real [MediaPlayerEngine] and the real player: callers on real threads prepare,
 * cancel, unload and seek while the main looper delivers the platform's callbacks, and at the end
 * every `MediaPlayer` the engine ever created must have been released. Every wait is bounded in real
 * time; the jitter is seeded and the seed is in each failure message.
 */
@RunWith(AndroidJUnit4::class)
class MediaPlayerEngineStormTest {

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

    private fun MediaPlayerEngine.heldPlayer(): MediaPlayer? =
        MediaPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .also { it.isAccessible = true }
            .get(this)
            .let { holder -> (holder as MutableStateFlow<*>).value as MediaPlayer? }

    private suspend fun pumpUntil(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 20.seconds
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            withContext(Dispatchers.Default) { delay(1L) }
        }
    }

    @Test
    fun `callers interleaved on the main thread preparing, cancelling and unloading leave no media player unreleased`() =
        runTest {
            val urls: List<String> = List(4) { "https://example.test/storm-$it.mp3" }
            urls.forEachIndexed { index: Int, url: String ->
                // The last source never finishes preparing by itself, so some loads are always in flight.
                val delayMs: Int = if (index == urls.lastIndex) Int.MAX_VALUE else 0
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(url), ShadowMediaPlayer.MediaInfo(10_000, delayMs))
            }
            repeat(ROUNDS) { round: Int ->
                val seed: Long = 4_243L * round + 29L
                created.clear()
                val engine = MediaPlayerEngine(context)
                val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), Dispatchers.Default)
                // The prepare calls live in a scope of their own, so a burst of callers can end while
                // its loads are still waiting for the platform; the main looper only runs between bursts,
                // because Robolectric's MediaPlayer shadow is not safe against a release on one thread
                // racing its own state change on another.
                val calls = CoroutineScope(SupervisorJob() + coroutineContext.minusKey(Job))
                repeat(BURSTS) { burst: Int ->
                    val workers: List<Job> = (0 until 4).map { w: Int ->
                        val random = Random(seed + 31L * burst + w)
                        launch {
                            repeat(OPS) {
                                when (random.nextInt(8)) {
                                    0, 1, 2 -> {
                                        val call: Job = calls.launch {
                                            player.prepare(AudioSource.Remote(urls[random.nextInt(urls.size)]))
                                        }
                                        if (random.nextBoolean()) {
                                            repeat(random.nextInt(4)) { yield() }
                                            call.cancel()
                                        }
                                    }
                                    3 -> player.unload()
                                    4 -> player.seekTo(random.nextLong(0L, 10_000L))
                                    5 -> player.pause()
                                    6 -> player.stop()
                                    else -> repeat(random.nextInt(4)) { yield() }
                                }
                            }
                        }
                    }
                    // Pumped from this very thread while the callers are suspended: one thread touches the shadows.
                    while (!workers.all { it.isCompleted }) {
                        shadowOf(Looper.getMainLooper()).idle()
                        yield()
                    }
                    repeat(3) {
                        shadowOf(Looper.getMainLooper()).idle()
                        withContext(Dispatchers.Default) { delay(1L) }
                    }
                }
                // Whatever is still loading unwinds on its own once nothing replaces it.
                player.unload()
                pumpUntil("seed $seed: every call returned") { calls.coroutineContext[Job]!!.children.none() }
                pumpUntil("seed $seed: the last load unwound") { engine.heldPlayer() == null }
                // Not wedged: a source that prepares at once still reaches Ready.
                val ready: Job = launch { player.prepare(AudioSource.Remote(urls[0])) }
                pumpUntil("seed $seed: ready after the storm") { player.stateFlow.value is PlayerState.Ready }
                ready.join()
                assertIs<PlayerState.Ready>(player.stateFlow.value, "seed $seed")

                player.release()
                pumpUntil("seed $seed: engine freed") { engine.heldPlayer() == null }
                assertNull(engine.heldPlayer(), "seed $seed")
                val leaked: List<Int> = created.toList().mapIndexedNotNull { index: Int, p: MediaPlayer ->
                    if (shadowOf(p).state == ShadowMediaPlayer.State.END) null else index
                }
                assertEquals(emptyList(), leaked, "seed $seed: of ${created.size} media players, these were never released; states ${leaked.map { shadowOf(created[it]).state }}; held ${engine.heldPlayer()}; player ${player.stateFlow.value}")
            }
        }

    private companion object {
        const val ROUNDS: Int = 8
        const val BURSTS: Int = 6
        const val OPS: Int = 12
    }
}
