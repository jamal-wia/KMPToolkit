package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
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
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** `PlaybackEngine.positionMs` is never negative, even when the platform reports a negative reading. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 35])
class MediaPlayerEnginePositionR8Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a negative platform reading is reported as zero`() {
        val url = "https://example.test/pos-${System.nanoTime()}.mp3"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(url), ShadowMediaPlayer.MediaInfo(10_000, 0))
        val created = mutableListOf<ShadowMediaPlayer>()
        ShadowMediaPlayer.setCreateListener { _, shadow -> created += shadow }
        val engine = MediaPlayerEngine(context)
        try {
            runBlocking {
                withTimeout(30_000) {
                    val loading = launch(Dispatchers.Default) { engine.load(AudioSource.Remote(url)) }
                    val deadline = TimeSource.Monotonic.markNow() + 15.seconds
                    while (!loading.isCompleted) {
                        shadowOf(Looper.getMainLooper()).idle()
                        if (deadline.hasPassedNow()) fail("load never finished")
                        withContext(Dispatchers.Default) { delay(1L) }
                    }
                }
            }
            val shadow: ShadowMediaPlayer = created.single()
            shadow.setCurrentPosition(-7)
            assertTrue(engine.positionMs() >= 0L, "position ${engine.positionMs()}")
            assertEquals(0L, engine.positionMs())
        } finally {
            engine.release()
            ShadowMediaPlayer.setCreateListener(null)
        }
    }
}
