package io.github.jamal_wia.kmptoolkit.video.player.javafx

import com.sun.net.httpserver.HttpServer
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A network source whose body stalls half way (the connection stays open and silent) and is then
 * released: the toolkit thread is shared with the app's whole UI, so freeing a stalled player must
 * not freeze it. Local HTTP server; every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxStalledNetworkR4AdversarialTest {

    private val gate = CountDownLatch(1)
    private lateinit var server: HttpServer
    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
        val clip: ByteArray = TestClip::class.java.classLoader.getResourceAsStream(TestClip.ASSET)!!.use { it.readBytes() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/clip.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, clip.size.toLong())
            val out = exchange.responseBody
            try {
                out.write(clip, 0, clip.size - 10)
                out.flush()
                gate.await(25L, TimeUnit.SECONDS)
                out.write(clip, clip.size - 10, 10)
            } catch (e: Exception) {
                // The player gave up on the connection: fine.
            } finally {
                runCatching { exchange.close() }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        gate.countDown()
        server.stop(0)
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun fxLatencyMs(): Long {
        val begun: Long = System.nanoTime()
        val ran = CountDownLatch(1)
        Platform.runLater { ran.countDown() }
        assertTrue(ran.await(30L, TimeUnit.SECONDS), "the FX thread did not answer within 30 s")
        return (System.nanoTime() - begun) / 1_000_000L
    }

    @Test
    fun `a network load whose body stalls near the end keeps the FX thread free and ends within its ready timeout`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 3_000L)
        val url = "http://127.0.0.1:${server.address.port}/clip.mp4"
        var outcome = "pending"
        val loader = Thread {
            outcome = try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Remote(url)) } }
                "ok"
            } catch (e: Throwable) {
                e::class.simpleName ?: "?"
            }
        }.apply { isDaemon = true; start() }
        var worstMs = 0L
        val begun: Long = System.nanoTime()
        while (loader.isAlive && System.nanoTime() - begun < 20_000_000_000L) {
            worstMs = maxOf(worstMs, fxLatencyMs())
            Thread.sleep(100L)
        }
        loader.join(5_000L)
        val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
        val afterMs: Long = fxLatencyMs()
        gate.countDown()
        engine.release()
        println("R4-STALL outcome=$outcome tookMs=$tookMs worstFxLatencyMs=$worstMs afterMs=$afterMs")
        assertTrue(!loader.isAlive, "the load never ended")
        assertTrue(outcome == "ok" || outcome == "LoadTimedOut", "unexpected outcome $outcome")
        assertTrue(tookMs < 10_000L, "the load took $tookMs ms with a 3 s ready timeout")
        assertTrue(worstMs < 2_000L, "the FX thread was blocked for $worstMs ms while the load waited on the network")
        assertTrue(afterMs < 2_000L, "the FX thread was blocked for $afterMs ms after the load gave up")
    }
}
