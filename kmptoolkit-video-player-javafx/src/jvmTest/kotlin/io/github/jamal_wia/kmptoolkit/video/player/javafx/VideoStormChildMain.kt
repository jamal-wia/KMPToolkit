package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * One storm thread of a player: random prepare, transport, rate and unload calls until [deadlineNanos].
 * Shared by [JavaFxPlayerStormAdversarialTest] and [VideoStormChildMain].
 */
internal fun driveStormPlayer(player: VideoPlayer, random: Random, deadlineNanos: Long, assets: List<String> = listOf(TestClip.AUDIO_ASSET)) {
    while (System.nanoTime() < deadlineNanos) {
        when (random.nextInt(12)) {
            0, 1 -> runBlocking {
                try {
                    withTimeout(6_000L) { player.prepare(VideoSource.Asset(assets.random(random))) }
                } catch (e: Exception) {
                    // A superseded, unloaded or timed-out prepare is an outcome, not a failure of the test.
                }
            }
            2 -> player.play()
            3 -> player.pause()
            4 -> player.seekTo(random.nextLong(0L, 3_200L))
            5 -> player.setPlaybackSpeed(0.25f + random.nextFloat() * 3f)
            6 -> player.setVolume(random.nextFloat())
            7 -> player.unload()
            8 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
            9 -> player.seekForward(random.nextLong(1L, 2_000L))
            10 -> player.replay()
            else -> player.setMuted(random.nextBoolean())
        }
        if (random.nextInt(4) == 0) Thread.sleep(random.nextLong(0L, 8L))
    }
}

/**
 * Entry point of a child JVM for the video-and-audio storm of [JavaFxPlayerStormAdversarialTest]: eight
 * players alternating the video clip and the audio-only clip, driven from eight threads for 5 s, then
 * released. Real video players disposed at this rate can hit the OpenJFX macOS display link race, so the
 * parent runs this through [runChildRetryingKnownCrash]. Prints `RESULT key=value` lines and exits.
 */
@OptIn(ToolkitInternalApi::class)
internal object VideoStormChildMain {

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.single() == "mixed") { "unknown scenario ${args.toList()}" }
        val errors: MutableList<String> = CopyOnWriteArrayList()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
        val players: List<VideoPlayer> = List(8) {
            createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 10_000L), VideoPlayerConfig(positionUpdateIntervalMs = 10L), Dispatchers.Default)
        }
        val deadline: Long = System.nanoTime() + 5_000_000_000L
        val assets: List<String> = listOf(TestClip.ASSET, TestClip.AUDIO_ASSET)
        val drivers: List<Thread> = players.mapIndexed { n, player -> thread(name = "mixed-$n") { driveStormPlayer(player, Random(n * 17L + 3), deadline, assets) } }
        drivers.forEach { it.join(30_000L) }
        println("RESULT driversHung=${drivers.count { it.isAlive }}")
        val releasers: List<Thread> = players.map { thread { it.release() } }
        releasers.forEach { it.join(15_000L) }
        println("RESULT releasersHung=${releasers.count { it.isAlive }}")
        val first: Boolean = drain()
        Thread.sleep(300L)
        println("RESULT drained=${first && drain()}")
        println("RESULT errors=${errors.joinToString(" | ")}")
        System.out.flush()
        exitProcess(0)
    }

    private fun drain(): Boolean {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        return done.await(8L, TimeUnit.SECONDS)
    }
}
