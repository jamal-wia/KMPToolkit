package io.github.jamal_wia.kmptoolkit.audio.player

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * GC reachability: a released player, its engine and the coroutines it started must all become
 * collectable, whatever the player was doing when it was released, because the documented way to
 * dispose of a player is exactly one call to release.
 */
class AudioPlayerReachabilityTest {

    private class TrackedEngine(private val loadMillis: Long) : PlaybackEngine {
        private var listener: PlaybackEngineListener? = null
        private var playing: Boolean = false
        override fun setListener(listener: PlaybackEngineListener?) {
            this.listener = listener
        }

        override suspend fun load(source: AudioSource) {
            if (loadMillis > 0) delay(loadMillis)
        }

        override fun start() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 1L
        override fun release() {
            playing = false
        }
    }

    @Test
    fun `released players and their engines become unreachable whatever they were doing`() {
        val refs: MutableList<WeakReference<Any>> = mutableListOf()
        repeat(40) { round: Int ->
            val engine = TrackedEngine(loadMillis = if (round % 5 == 4) 60_000L else 0L)
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(positionUpdateIntervalMs = 5L), Dispatchers.Default)
            runBlocking {
                withTimeout(10_000) {
                    val source = AudioSource.File("/gc/$round.mp3")
                    when (round % 5) {
                        0 -> player.prepare(source)
                        1 -> {
                            player.prepare(source)
                            player.play()
                            delay(15)
                        }
                        2 -> {
                            player.prepare(source)
                            player.play()
                            player.unload()
                            player.prepare(source)
                        }
                        3 -> {
                            player.prepare(source)
                            player.play()
                            engine.listener()?.onCompleted()
                        }
                        else -> {
                            val call = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { player.prepare(source) }
                            delay(10)
                            player.release()
                            call.cancel()
                            call.join()
                        }
                    }
                }
            }
            player.release()
            refs += WeakReference(player)
            refs += WeakReference(engine)
        }
        var alive: Int = refs.size
        for (attempt in 0 until 50) {
            System.gc()
            System.runFinalization()
            alive = refs.count { it.get() != null }
            if (alive == 0) break
            Thread.sleep(40)
        }
        assertEquals(0, alive, "players or engines still reachable after release")
    }

    private fun TrackedEngine.listener(): PlaybackEngineListener? =
        TrackedEngine::class.java.getDeclaredField("listener").also { it.isAccessible = true }.get(this) as PlaybackEngineListener?
}
