package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Entry point of a child JVM for [ToolkitKeepAliveAfterReleaseProcessTest]: an app that plays one audio clip through the public factory,
 * releases the player and lets `main` return. It never calls `exitProcess`. The scenario in `args` decides the one other thing it does:
 * `exit` calls `Platform.exit()` after the release (the documented way to end such a program), `return` calls nothing. Prints `RESULT key=value`
 * lines; in `return` a daemon watchdog reports the live non-daemon threads 6 s after `main` returned (the JVM is then still running, which the
 * parent checks by the process itself).
 */
internal object ToolkitKeepAliveAfterReleaseMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val scenario: String = args.single()
        val player = createJavaFxVideoPlayer()
        runBlocking { withTimeout(30_000L) { player.prepare(VideoSource.Asset(TestClip.AUDIO_ASSET)) } }
        player.play()
        runBlocking { withTimeout(30_000L) { player.playbackPositionFlow.first { it > 100L } } }
        player.release()
        when (scenario) {
            "exit" -> {
                println("RESULT released=true")
                System.out.flush()
                Platform.exit()
            }
            "return" -> {
                thread(isDaemon = true, name = "keep-alive-watchdog") {
                    // timing-hygiene-ignore: TH1 inject the JVM gets 6 s to end on its own; the watchdog only reports what keeps it alive
                    Thread.sleep(6_000L)
                    val alive: List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive && !it.isDaemon && it.name != "main" }.map { it.name }
                    println("RESULT nonDaemonAfter6s=$alive")
                    System.out.flush()
                }
                println("RESULT released=true")
                System.out.flush()
            }
            else -> error("unknown scenario $scenario")
        }
    }
}
