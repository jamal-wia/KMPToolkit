package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Entry point of a child JVM for [FreshToolkitProcessAdversarialTest]: a toolkit that no earlier
 * test has started. Prints `RESULT key=value` lines and exits.
 */
internal object FreshToolkitMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.single()) {
                "many" -> askFrom(16)
                "host-first" -> {
                    startHostToolkit()
                    askFrom(8)
                    println("RESULT implicitExit=${Platform.isImplicitExit()}")
                }
                "host-concurrent" -> {
                    val gate = CountDownLatch(1)
                    val host = thread { gate.await(); runCatching { startHostToolkit() }.onFailure { println("RESULT hostStartup=${it::class.simpleName}") } }
                    gate.countDown()
                    askFrom(8)
                    host.join(30_000L)
                }
                "exited" -> {
                    startHostToolkit()
                    Platform.exit()
                    Thread.sleep(500L)
                    val begun: Long = System.nanoTime()
                    askFrom(8)
                    println("RESULT elapsedMs=${(System.nanoTime() - begun) / 1_000_000L}")
                }
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

    private fun askFrom(callers: Int) {
        val gate = CountDownLatch(1)
        val answers: List<AtomicReference<Boolean?>> = List(callers) { AtomicReference<Boolean?>(null) }
        val threads: List<Thread> = List(callers) { n ->
            thread { gate.await(); answers[n].set(isJavaFxMediaAvailable()) }
        }
        gate.countDown()
        threads.forEach { it.join(60_000L) }
        println("RESULT hung=${threads.count { it.isAlive }}")
        println("RESULT answers=${answers.map { it.get() }.groupingBy { it }.eachCount()}")
    }
}
