package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * The engine against a real libvlc, playing the checked-in 2-second clip. Skipped — not failed —
 * when [isVlcAvailable] is false, which includes a VLC built for another CPU architecture than the
 * test JVM (see `docs/kmptoolkit-video-player-vlcj/06-testing.md`).
 */
@OptIn(ToolkitInternalApi::class)
class VlcjVideoEngineTest {

    private val listener = RecordingListener()
    private lateinit var engine: VlcjVideoEngine
    private val servers: MutableList<AutoCloseable> = CopyOnWriteArrayList()

    @BeforeTest
    fun requireVlc() {
        assumeTrue("VLC (libvlc) is not available to this JVM", isVlcAvailable())
        engine = VlcjVideoEngine(vlcArgs = listOf("--quiet", "--aout=dummy"))
        engine.setListener(listener)
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.release()
        servers.forEach { runCatching { it.close() } }
    }

    // --- loading ---------------------------------------------------------------------------------

    @Test
    fun `a local file loads with its duration and picture size`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))

        assertEquals(TestClip.DURATION_MS.toDouble(), engine.durationMs().toDouble(), 100.0)
        assertEquals(0L, engine.positionMs())
        assertEquals(engine.durationMs(), engine.bufferedPositionMs(), "a local source is fully buffered")
        assertEquals(VideoSize(TestClip.WIDTH, TestClip.HEIGHT), listener.sizes.lastOrNull())
        assertNull(engine.frames.value, "no picture before the first start")
    }

    @Test
    fun `an asset loads from the classpath`() = runBlocking<Unit> {
        engine.load(VideoSource.Asset(TestClip.ASSET_PATH))

        assertTrue(engine.durationMs() > 0L)
    }

    @Test
    fun `a file VLC cannot demux fails the load with VlcPlaybackException`() = runBlocking<Unit> {
        val garbage: File = Files.createTempFile("not-a-video", ".mp4").toFile()
        garbage.writeBytes(ByteArray(4096) { (it * 31).toByte() })
        try {
            assertFailsWith<VlcPlaybackException> { engine.load(VideoSource.File(garbage.path)) }
        } finally {
            garbage.delete()
        }
        assertEquals(0L, engine.durationMs(), "nothing playable may be left behind")
    }

    @Test
    fun `an HTTP 404 fails the load with VlcPlaybackException`() = runBlocking<Unit> {
        val server: HttpServer = startServer { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        assertFailsWith<VlcPlaybackException> {
            withTimeout(LOAD_TIMEOUT_MS) { engine.load(VideoSource.Remote(server.url("/missing.mp4"))) }
        }
    }

    @Test
    fun `a remote source plays and sends the supported headers`() = runBlocking<Unit> {
        val seen: MutableMap<String, String> = ConcurrentHashMap()
        val bytes: ByteArray = TestClip.file.readBytes()
        val server: HttpServer = startServer { exchange ->
            exchange.requestHeaders.getFirst("User-Agent")?.let { seen["User-Agent"] = it }
            exchange.requestHeaders.getFirst("Referer")?.let { seen["Referer"] = it }
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        withTimeout(LOAD_TIMEOUT_MS) {
            engine.load(
                VideoSource.Remote(
                    server.url("/clip.mp4"),
                    headers = mapOf("User-Agent" to "KmpToolkitTest/1.0", "Referer" to "https://app.test/"),
                ),
            )
        }

        assertEquals("KmpToolkitTest/1.0", seen["User-Agent"])
        assertEquals("https://app.test/", seen["Referer"])
        assertTrue(engine.durationMs() > 0L)
    }

    @Test
    fun `cancelling a load that never completes leaves the engine reusable`() = runBlocking<Unit> {
        val silent = ServerSocket(0)
        servers += silent
        val held: MutableList<Socket> = CopyOnWriteArrayList()
        thread(isDaemon = true) {
            // Accept and never answer, so VLC waits on the response forever.
            runCatching { while (true) held += silent.accept() }
        }
        servers += AutoCloseable { held.forEach { runCatching { it.close() } } }

        val load = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            engine.load(VideoSource.Remote("http://127.0.0.1:${silent.localPort}/hang.mp4"))
        }
        delay(1_000L)
        load.cancel()
        withTimeout(CANCEL_TIMEOUT_MS) { load.join() }

        assertTrue(load.isCancelled)
        assertFailsWith<CancellationException> { load.await() }
        assertEquals(0L, engine.durationMs())

        engine.load(VideoSource.File(TestClip.file.path))
        assertTrue(engine.durationMs() > 0L)
    }

    // --- playback ----------------------------------------------------------------------------------

    @Test
    fun `playing delivers opaque frames of the picture size and advances the playhead`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))

        engine.start()

        val frame: VideoFrame = awaitValue { engine.frames.value }
        assertEquals(TestClip.WIDTH, frame.width)
        assertEquals(TestClip.HEIGHT, frame.height)
        assertTrue(frame.pixels.size >= frame.width * frame.height)
        assertTrue(frame.pixels.take(frame.width * frame.height).all { it ushr 24 == 0xFF })
        awaitTrue { engine.positionMs() > 300L }
    }

    @Test
    fun `pause holds the playhead and a seek while paused moves it`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()
        awaitTrue { engine.positionMs() > 200L }

        engine.pause()
        delay(300L)
        val held: Long = engine.positionMs()
        delay(500L)
        assertEquals(held.toDouble(), engine.positionMs().toDouble(), 50.0, "paused playhead must not move")

        engine.seekTo(1_500L)
        awaitTrue { kotlin.math.abs(engine.positionMs() - 1_500L) <= 150L }
        assertEquals(0, listener.completions)
    }

    @Test
    fun `a seek before the first start is applied when playback begins`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))

        engine.seekTo(1_000L)
        assertEquals(1_000L, engine.positionMs())
        engine.start()

        awaitTrue { engine.positionMs() >= 1_000L }
        awaitTrue { listener.completions == 1 }
    }

    @Test
    fun `playing to the end reports completion once and parks the playhead at the end`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()

        awaitTrue(timeoutMs = 8_000L) { listener.completions == 1 }
        delay(500L)

        assertEquals(1, listener.completions)
        assertEquals(engine.durationMs(), engine.positionMs())
        assertTrue(listener.failures.isEmpty())
    }

    @Test
    fun `start after completion plays the source again`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()
        awaitTrue(timeoutMs = 8_000L) { listener.completions == 1 }

        engine.seekTo(0L)
        engine.start()

        awaitTrue(timeoutMs = 8_000L) { listener.completions == 2 }
    }

    @Test
    fun `looping never reports completion and keeps producing frames`() = runBlocking<Unit> {
        engine.setLooping(true)
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()

        delay(TestClip.DURATION_MS * 2 + 1_500L)
        val before: VideoFrame? = engine.frames.value
        delay(500L)

        assertEquals(0, listener.completions)
        assertTrue(listener.failures.isEmpty())
        assertTrue(engine.frames.value !== before, "frames must keep coming across the loop point")
    }

    @Test
    fun `speed and volume can be set before and during playback`() = runBlocking<Unit> {
        engine.setVolume(0.2f)
        engine.setSpeed(2f)
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()

        engine.setVolume(0f)
        engine.setSpeed(0.5f)
        engine.setSpeed(2f)

        // At 2x a 2-second clip ends well inside 1x's running time.
        awaitTrue(timeoutMs = 1_900L) { listener.completions == 1 }
    }

    // --- release -----------------------------------------------------------------------------------

    @Test
    fun `release during playback is idempotent and silences every callback`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()
        awaitValue { engine.frames.value }

        engine.release()
        val eventsAtRelease: Int = listener.events.size
        engine.release()
        delay(TestClip.DURATION_MS + 1_000L)

        assertEquals(eventsAtRelease, listener.events.size, "no callback may arrive after release")
        assertNull(engine.frames.value)
        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        engine.start()
        engine.seekTo(500L)
    }

    @Test
    fun `load after release works`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.release()

        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()

        val frame: VideoFrame = awaitValue { engine.frames.value }
        assertEquals(TestClip.WIDTH, frame.width)
    }

    @Test
    fun `a new load replaces the previous source without callbacks from the old one`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))
        engine.start()
        awaitTrue { engine.positionMs() > 1_000L }

        engine.load(VideoSource.Asset(TestClip.ASSET_PATH))
        delay(TestClip.DURATION_MS + 500L)

        assertEquals(0, listener.completions, "the first source's end must not be reported")
        assertEquals(0L, engine.positionMs())
    }

    @Test
    fun `a failed load after a good one leaves nothing playable`() = runBlocking<Unit> {
        engine.load(VideoSource.File(TestClip.file.path))

        assertFailsWith<java.io.FileNotFoundException> { engine.load(VideoSource.File("/nonexistent.mp4")) }

        assertEquals(0L, engine.durationMs())
        engine.start()
        delay(500L)
        assertNull(engine.frames.value)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun startServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        servers += AutoCloseable { server.stop(0) }
        return server
    }

    private fun HttpServer.url(path: String): String = "http://127.0.0.1:${address.port}$path"

    private suspend fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private suspend fun <T : Any> awaitValue(timeoutMs: Long = 5_000L, read: () -> T?): T {
        var value: T? = null
        awaitTrue(timeoutMs) {
            value = read()
            value != null
        }
        return assertNotNull(value)
    }

    private companion object {
        const val POLL_MS = 20L
        const val LOAD_TIMEOUT_MS = 15_000L
        const val CANCEL_TIMEOUT_MS = 5_000L
    }
}
