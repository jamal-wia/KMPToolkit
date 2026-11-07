package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Entry point of a child JVM for [ToolkitExitLifecycleProcessAdversarialTest]: a host that owns the
 * toolkit and calls `Platform.exit()` while the engine is in use. Prints `RESULT key=value` lines.
 */
@OptIn(ToolkitInternalApi::class)
internal object ExitLifecycleMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            startHostToolkit()
            when (args.single()) {
                "immediate" -> immediate()
                "playing" -> playing()
                "during-load" -> duringLoad()
                "implicit" -> implicit()
                "callback" -> callback()
                else -> error("unknown scenario")
            }
        } catch (e: Throwable) {
            println("RESULT crashed=${e::class.simpleName}: ${e.message}")
        }
        System.out.flush()
        exitProcess(0)
    }

    private fun startHostToolkit() {
        val up = CountDownLatch(1)
        Platform.startup { up.countDown() }
        check(up.await(30L, TimeUnit.SECONDS)) { "the host toolkit did not start" }
    }

    private fun load(engine: JavaFxVideoEngine): String = try {
        runBlocking { withTimeout(25_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
        "ok"
    } catch (e: Throwable) {
        e::class.simpleName ?: "?"
    }

    private fun ms(since: Long): Long = (System.nanoTime() - since) / 1_000_000L

    private fun immediate() {
        check(isJavaFxMediaAvailable()) // the runtime has seen the toolkit running
        Platform.exit()
        val begun: Long = System.nanoTime()
        // No pause: how soon does the runtime notice the exit?
        val answers: List<Boolean> = List(20) { isJavaFxMediaAvailable().also { Thread.sleep(25L) } }
        println("RESULT answers=$answers")
        val engine = JavaFxVideoEngine(readyTimeoutMs = 4_000L)
        val loadBegun: Long = System.nanoTime()
        println("RESULT load=${load(engine)}")
        println("RESULT loadMs=${ms(loadBegun)}")
        println("RESULT totalMs=${ms(begun)}")
    }

    private fun playing() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 4_000L)
        println("RESULT firstLoad=${load(engine)}")
        engine.start()
        Thread.sleep(300L)
        Platform.exit()
        val threadsBefore: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
        val begun: Long = System.nanoTime()
        engine.seekTo(1_000L)
        engine.start()
        engine.pause()
        engine.setVolume(0.5f)
        engine.setSpeed(1.5f)
        engine.setLooping(true)
        engine.positionMs()
        engine.durationMs()
        engine.release()
        engine.release()
        println("RESULT callsMs=${ms(begun)}")
        val reloadBegun: Long = System.nanoTime()
        println("RESULT reload=${load(engine)}")
        println("RESULT reloadMs=${ms(reloadBegun)}")
        val manyBegun: Long = System.nanoTime()
        val answers: List<Boolean> = List(300) { isJavaFxMediaAvailable() }
        println("RESULT availableAfter=${answers.toSet()}")
        println("RESULT manyMs=${ms(manyBegun)}")
        val engines: List<JavaFxVideoEngine> = List(20) { JavaFxVideoEngine(readyTimeoutMs = 4_000L) }
        val failures = AtomicInteger()
        val loadsBegun: Long = System.nanoTime()
        engines.forEach { if (load(it) != "ok") failures.incrementAndGet() }
        println("RESULT twentyLoadsMs=${ms(loadsBegun)} failures=${failures.get()}")
        Thread.sleep(500L)
        println("RESULT threadGrowth=${Thread.getAllStackTraces().keys.count { it.isAlive } - threadsBefore}")
    }

    private fun duringLoad() {
        val outcomes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val gate = CountDownLatch(1)
        val begun: Long = System.nanoTime()
        val workers: List<Thread> = List(8) {
            thread {
                gate.await()
                val engine = JavaFxVideoEngine(readyTimeoutMs = 3_000L)
                outcomes += load(engine)
                engine.release()
            }
        }
        gate.countDown()
        Thread.sleep(Random.nextLong(0L, 40L))
        Platform.exit()
        workers.forEach { it.join(60_000L) }
        println("RESULT hung=${workers.count { it.isAlive }}")
        println("RESULT outcomes=${outcomes.groupingBy { it }.eachCount().toSortedMap()}")
        println("RESULT totalMs=${ms(begun)}")
    }

    /** The host's own window closes, and its implicit exit takes the toolkit (and the engine) down. */
    private fun implicit() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 4_000L)
        println("RESULT firstLoad=${load(engine)}")
        engine.start()
        val closed = CountDownLatch(1)
        Platform.runLater {
            val stage = javafx.stage.Stage()
            stage.scene = javafx.scene.Scene(javafx.scene.Group(), 50.0, 50.0)
            stage.show()
            stage.close()
            closed.countDown()
        }
        closed.await(10L, TimeUnit.SECONDS)
        Thread.sleep(700L)
        println("RESULT startupSays=" + try { Platform.startup { }; "no throw" } catch (e: Throwable) { "${e::class.simpleName}: ${e.message}" })
        val ranLater = CountDownLatch(1)
        runCatching { Platform.runLater { ranLater.countDown() } }
        println("RESULT runLaterRuns=${ranLater.await(1L, TimeUnit.SECONDS)}")
        val begun: Long = System.nanoTime()
        engine.seekTo(500L)
        engine.pause()
        engine.release()
        println("RESULT callsMs=${ms(begun)}")
        println("RESULT availableAfter=${isJavaFxMediaAvailable()}")
        val reloadBegun: Long = System.nanoTime()
        println("RESULT reload=${load(engine)}")
        println("RESULT reloadMs=${ms(reloadBegun)}")
    }

    /** The app exits the toolkit from inside the engine's own completion callback, then releases. */
    private fun callback() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 4_000L)
        val done = CountDownLatch(1)
        engine.setListener(object : io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener {
            override fun onCompleted() {
                Platform.exit()
                engine.release()
                engine.seekTo(0L)
                engine.start()
                done.countDown()
            }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: io.github.jamal_wia.kmptoolkit.video.player.VideoSize?) = Unit
        })
        println("RESULT firstLoad=${load(engine)}")
        engine.seekTo(2_900L)
        engine.start()
        println("RESULT completedInTime=${done.await(10L, TimeUnit.SECONDS)}")
        Thread.sleep(300L)
        val reloadBegun: Long = System.nanoTime()
        println("RESULT reload=${load(engine)}")
        println("RESULT reloadMs=${ms(reloadBegun)}")
    }
}
