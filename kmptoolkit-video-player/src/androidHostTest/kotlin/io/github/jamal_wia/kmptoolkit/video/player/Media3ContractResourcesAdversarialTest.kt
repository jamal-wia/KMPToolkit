package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.exoplayer.ExoPlayer
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Contract and resource attacks on the Media3 engine under the real player: surfaces handed out around
 * release, released players being collectable, odd sources and odd speeds. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3ContractResourcesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.mp4")

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    @Test
    fun `released players and their engines become unreachable`() {
        val players: MutableList<WeakReference<Any>> = mutableListOf()
        val exos: MutableList<WeakReference<ExoPlayer>> = mutableListOf()
        repeat(CYCLES) {
            val engine: Media3VideoEngine = newEngine(context, created = { exos += WeakReference(it) })
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
            players += WeakReference(player)
            players += WeakReference(engine)
            runSuspending { player.prepare(source) }.getOrThrow()
            player.play()
            pump(2)
            player.release()
            pump(2)
        }
        var rounds = 0
        while (rounds < GC_ROUNDS && (players.any { it.get() != null } || exos.any { it.get() != null })) {
            System.gc()
            pump(1)
            Thread.sleep(20L)
            rounds++
        }
        val leakedObjects: Int = players.count { it.get() != null }
        val leakedExo: Int = exos.count { it.get() != null }
        assertEquals(0, leakedObjects, "$leakedObjects released players or engines are still reachable")
        assertEquals(0, leakedExo, "$leakedExo released ExoPlayers are still reachable")
    }
}

private const val CYCLES: Int = 20
private const val GC_ROUNDS: Int = 60
