package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resource starvation by the network: a server that accepts the connection and never answers. The
 * engine's readyTimeoutMs is documented as the bound on how long a load waits; opening the Media
 * itself runs before that bound starts (see FxPlayback.open).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSilentServerR4AdversarialTest {

    private val server = ServerSocket(0)
    private val held: MutableList<Socket> = CopyOnWriteArrayList()
    private val running = AtomicBoolean(true)

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        thread(name = "silent-server", isDaemon = true) {
            while (running.get()) {
                try {
                    held += server.accept()
                } catch (e: Exception) {
                    return@thread
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        running.set(false)
        held.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    @Test
    fun `a load against a server that never answers ends at its ready timeout, not when the server gives up`() = runBlocking<Unit> {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 2_000L)
        val url = "http://127.0.0.1:${server.localPort}/clip.mp4"
        val begun: Long = System.nanoTime()
        val load = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            try {
                engine.load(VideoSource.Remote(url))
                "ok"
            } catch (e: Throwable) {
                e::class.simpleName
            }
        }
        val outcome: String? = withTimeoutOrNull(12_000L) { load.await() }
        val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
        if (outcome == null) {
            load.cancel()
            // The caller's own cancellation is the last resort: does it return?
            val afterCancel: Boolean = withTimeoutOrNull(5_000L) { load.join(); true } ?: false
            engine.release()
            throw AssertionError("the load was still running after 12 s (readyTimeoutMs 2 s); returned after cancel: $afterCancel")
        }
        engine.release()
        assertTrue(tookMs < 8_000L, "outcome $outcome after $tookMs ms")
    }
}
