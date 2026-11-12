package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (concurrency storm, release racing every operation, on real JavaFX): a seeded random sequence of player calls, looping
 * flips included, each on the FX application thread or a plain thread, and a `release()` at a random step from either kind of
 * thread. Then more calls from both kinds of thread against the released player. Nothing may reach an uncaught-exception handler
 * (the FX thread's included: an NPE inside JavaFX at a loop point was round 3's J4), the released player reads `Idle` with
 * its flows reset, every call after the release is silent, and a fresh player still prepares and plays afterwards.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxReleaseRaceFuzzR9AdversarialTest {

    private fun onFxThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try { block() } catch (t: Throwable) { failure = t } finally { done.countDown() }
        }
        check(done.await(15L, TimeUnit.SECONDS)) { "the FX thread did not run the block" }
        failure?.let { throw it }
    }

    private fun op(player: VideoPlayer, rnd: Random, onFx: Boolean) {
        when (rnd.nextInt(13)) {
            0, 1 -> player.play()
            2 -> player.pause()
            3 -> player.stop()
            4 -> player.replay()
            5 -> player.seekTo(rnd.nextLong(0L, 3_100L))
            6 -> player.seekForward(rnd.nextLong(100L, 1_500L))
            7 -> player.seekBackward(rnd.nextLong(100L, 1_500L))
            8, 9 -> player.setPlaybackSpeed(listOf(0.5f, 1f, 2f, 3f)[rnd.nextInt(4)])
            10 -> player.setRepeatMode(if (rnd.nextBoolean()) RepeatMode.One else RepeatMode.Off)
            11 -> player.setVolume(rnd.nextInt(0, 11) / 10f)
            else -> {
                player.unload()
                // prepare() needs the FX thread to build the player: blocking the FX thread on it would be the caller's own deadlock.
                if (!onFx) runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            }
        }
    }

    @Test
    fun `release at a random step from the FX thread or a plain thread among random calls is clean`() {
        assumeJavaFxMedia()
        val uncaught: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t: Thread, e: Throwable -> uncaught += "${t.name}: $e" }
        val failures: MutableList<String> = mutableListOf()
        try {
            val from: Int = System.getenv("KMPTOOLKIT_R9_REL_FROM")?.toIntOrNull() ?: 1
            val count: Int = System.getenv("KMPTOOLKIT_R9_REL_SEEDS")?.toIntOrNull() ?: 14
            for (seed: Int in from until from + count) {
                val rnd = Random(seed)
                val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
                val player: VideoPlayer = createVideoPlayer(engine)
                try {
                    runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                    val releaseAt: Int = rnd.nextInt(3, 14)
                    for (step: Int in 0 until 16) {
                        if (step == releaseAt) {
                            if (rnd.nextBoolean()) onFxThread { player.release() } else player.release()
                        } else {
                            val onFx: Boolean = rnd.nextBoolean()
                            val seedForOp: Int = rnd.nextInt()
                            if (onFx) onFxThread { op(player, Random(seedForOp), true) } else op(player, Random(seedForOp), false)
                        }
                        val pause: Long = listOf(0L, 0L, 1L, 10L, 60L, 200L, 500L)[rnd.nextInt(7)]
                        if (pause > 0L) Thread.sleep(pause)
                    }
                    Thread.sleep(300L)
                    val state: VideoPlayerState = player.stateFlow.value
                    // A prepare after the release sets Error(VideoPlayerReleasedException) by contract, so that is as clean as Idle.
                    val clean: Boolean = state == VideoPlayerState.Idle ||
                        (state is VideoPlayerState.Error && state.cause is io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException)
                    if (!clean) failures += "seed $seed: after the release the state is $state"
                    if (player.playbackPositionFlow.value != 0L || player.videoSizeFlow.value != null || player.isBufferingFlow.value) {
                        failures += "seed $seed: flows not reset after the release: position ${player.playbackPositionFlow.value}, size ${player.videoSizeFlow.value}, buffering ${player.isBufferingFlow.value}"
                    }
                } catch (t: Throwable) {
                    failures += "seed $seed: ${t::class.java.simpleName}: ${t.message?.take(150)}"
                } finally {
                    player.release()
                }
            }
            val fresh: VideoPlayer = createJavaFxVideoPlayer()
            try {
                runBlocking { withTimeout(30_000L) { fresh.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                fresh.setPlaybackSpeed(3f)
                fresh.play()
                awaitTrue(timeoutMs = 10_000L, message = { "the fresh player never completed: ${fresh.stateFlow.value}" }) { fresh.stateFlow.value is VideoPlayerState.Completed }
            } finally {
                fresh.release()
            }
            Thread.sleep(300L)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(emptyList(), failures + uncaught.toList(), "release race")
    }
}
