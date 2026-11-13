package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Entry point of a child JVM for the two video leak checks that release real video players within milliseconds
 * of `start()`, the window of the OpenJFX macOS display link race (see the "Known issue" section of
 * `docs/kmptoolkit-video-player-javafx/05-platform-notes.md`): the parent runs it through
 * [runChildRetryingKnownCrash]. Scenarios, both unchanged from the in-process tests they came from:
 * - `threads` (from JavaFxEngineAdversarialTest): thirty create, load, start, release cycles; prints `RESULT baseline=` and `RESULT after=` (live threads).
 * - `reachable` (from JavaFxResourceChaosAdversarialTest): fifteen engines released after 40 ms; prints `RESULT stillReachable=` (how many are still reachable).
 */
@OptIn(ToolkitInternalApi::class)
internal object VideoLeakChildMain {

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            "threads" -> threads()
            "reachable" -> reachable()
            else -> error("unknown scenario ${args.toList()}")
        }
        System.out.flush()
        exitProcess(0)
    }

    private fun load(engine: JavaFxVideoEngine) = runBlocking {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
    }

    private fun drainFxThread() {
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        check(done.await(8L, TimeUnit.SECONDS)) { "the FX thread did not drain" }
    }

    private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

    private fun threads() {
        // Warm up: the first player starts the toolkit's and the media framework's own threads.
        JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { load(it); it.start(); Thread.sleep(100L); it.release() }
        drainFxThread()
        System.gc()
        val baseline: Int = liveThreads()
        repeat(30) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            load(engine)
            engine.start()
            Thread.sleep(30L)
            engine.release()
        }
        drainFxThread()
        System.gc()
        Thread.sleep(500L)
        println("RESULT baseline=$baseline")
        println("RESULT after=${liveThreads()}")
    }

    private fun reachable() {
        val references: MutableList<WeakReference<JavaFxVideoEngine>> = mutableListOf()
        repeat(15) {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            load(engine)
            engine.start()
            Thread.sleep(40L)
            engine.release()
            references += WeakReference(engine)
        }
        drainFxThread()
        val deadline: Long = System.nanoTime() + 10_000_000_000L
        while (references.any { it.get() != null } && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(50L)
        }
        println("RESULT stillReachable=${references.count { it.get() != null }}")
    }
}
