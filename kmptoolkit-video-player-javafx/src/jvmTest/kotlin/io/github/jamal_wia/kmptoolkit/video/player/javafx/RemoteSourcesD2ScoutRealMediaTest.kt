package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.scene.media.MediaException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 scout (executor ko), angle (d): remote sources over a local HTTP server started by the test (127.0.0.1 only, no external
 * network), real JavaFX media, so it runs under the exclusive slot. The oracle is the documented text of
 * `03-guide.md` "Sources on desktop" and "Errors you can see", `04-api-reference.md` and `05-platform-notes.md` "Formats":
 *
 * - `Remote(url)` is "passed to JavaFX as is", and a target JavaFX "cannot reach or open" arrives as `Error(MediaException)`, with
 *   `MEDIA_INACCESSIBLE` for a refused connection and `MEDIA_UNAVAILABLE` for an HTTP 404 on macOS (the type of a 404 is pinned
 *   by `RemoteSourceFailuresD2ScoutDocsRealMediaTest`, not here);
 * - "a failure during playback (a network drop mid-stream) arrives the same way, as `Error(MediaException)`";
 * - "the engine waits ... and then fails the load, so `prepare` never hangs", `readyTimeoutMs` bounding a slow server, and a stalled
 *   load that "keeps the FX thread free";
 * - "for MP4 over HTTP, the `moov` atom must be at the start of the file ... or JavaFX stalls until the whole file has downloaded":
 *   the checked-in clip has its `moov` first, so it must not need the last bytes of the file to become ready.
 *
 * Every wait is a flow wait or a latch wait with a hang bound that fails with its own message. The server is a raw socket server so a
 * response can be cut off, withheld, dribbled or reset.
 */
@OptIn(ToolkitInternalApi::class)
class RemoteSourcesD2ScoutRealMediaTest {

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    /** A one-request-per-connection HTTP/1.1 server that answers with what [handler] writes. */
    private class RawServer(private val handler: (Request, Socket, OutputStream) -> Unit) {
        private val socket: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val open: MutableList<Socket> = CopyOnWriteArrayList()
        private val running: AtomicBoolean = AtomicBoolean(true)
        val requests: MutableList<String> = CopyOnWriteArrayList()
        val port: Int get() = socket.localPort

        init {
            thread(name = "scout-ko-http-accept", isDaemon = true) {
                while (running.get()) {
                    val client: Socket = try {
                        socket.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    open += client
                    thread(name = "scout-ko-http-conn", isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine: String = input.readLine() ?: return
                val parts: List<String> = requestLine.split(' ')
                val headers: MutableMap<String, String> = linkedMapOf()
                while (true) {
                    val line: String = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon: Int = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val request = Request(parts[0], parts.getOrElse(1) { "/" }, headers)
                requests += "${request.method} ${request.path} range=${headers["range"]}"
                handler(request, client, client.getOutputStream())
            } catch (e: Exception) {
                // The player gave up on the connection: fine.
            } finally {
                runCatching { client.close() }
            }
        }

        fun close() {
            running.set(false)
            runCatching { socket.close() }
            open.forEach { runCatching { it.close() } }
        }
    }

    private lateinit var clip: ByteArray
    private var server: RawServer? = null
    private val players: MutableList<VideoPlayer> = CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        clip = TestClip::class.java.classLoader.getResourceAsStream(TestClip.ASSET)!!.use { it.readBytes() }
    }

    @AfterTest
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
        server?.close()
    }

    private fun serve(handler: (Request, Socket, OutputStream) -> Unit): RawServer = RawServer(handler).also { server = it }

    private fun url(server: RawServer, path: String = "/clip.mp4"): String = "http://127.0.0.1:${server.port}$path"

    private fun newPlayer(): VideoPlayer =
        createJavaFxVideoPlayer(VideoPlayerConfig(positionUpdateIntervalMs = 25L)).also { players += it }

    private fun head(out: OutputStream, status: String, vararg lines: String) {
        out.write((listOf(status) + lines + listOf("Connection: close", "", "")).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Answers a GET of the clip the way a static file server does, with or without byte ranges. [tailGate] withholds the last
     * [withheld] bytes of any response that reaches the end of the file until the latch opens; [resetAtOffset] cuts the connection
     * with a reset once the response has delivered the file up to that offset (a response that ends before it is served whole).
     */
    private fun serveClip(
        request: Request,
        socket: Socket,
        out: OutputStream,
        ranges: Boolean,
        tailGate: CountDownLatch? = null,
        withheld: Int = 200,
        resetAtOffset: Int? = null,
    ) {
        val range: String? = request.headers["range"]
        val match: MatchResult? = range?.takeIf { ranges }?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) }
        val from: Int = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val to: Int = minOf(match?.groupValues?.get(2)?.toIntOrNull() ?: (clip.size - 1), clip.size - 1)
        if (match != null) {
            head(out, "HTTP/1.1 206 Partial Content", "Content-Type: video/mp4", "Accept-Ranges: bytes", "Content-Range: bytes $from-$to/${clip.size}", "Content-Length: ${to - from + 1}")
        } else {
            head(out, "HTTP/1.1 200 OK", "Content-Type: video/mp4", "Accept-Ranges: ${if (ranges) "bytes" else "none"}", "Content-Length: ${clip.size}")
        }
        if (request.method != "GET") {
            out.flush()
            return
        }
        var end: Int = to
        if (resetAtOffset != null && to >= resetAtOffset) {
            out.write(clip, from, maxOf(0, resetAtOffset - from))
            out.flush()
            // A reset, not an orderly close: what a dropped connection looks like.
            socket.setSoLinger(true, 0)
            socket.close()
            return
        }
        if (tailGate != null && to >= clip.size - 1) end = to - withheld
        out.write(clip, from, end - from + 1)
        out.flush()
        if (end < to) {
            tailGate?.await(HANG_MS, TimeUnit.MILLISECONDS)
            out.write(clip, end + 1, to - end)
            out.flush()
        }
    }

    /** Loads [url] through the engine with its own ready timeout and returns what the load ended with, `null` for a success. */
    private fun loadOutcome(url: String, readyTimeoutMs: Long): Throwable? {
        val engine = JavaFxVideoEngine(readyTimeoutMs = readyTimeoutMs)
        return try {
            runBlocking { withTimeout(readyTimeoutMs + 20_000L) { engine.load(VideoSource.Remote(url)) } }
            null
        } catch (e: Throwable) {
            e
        } finally {
            engine.release()
        }
    }

    private fun prepare(player: VideoPlayer, source: VideoSource): VideoPlayerState {
        runBlocking { withTimeout(PREPARE_HANG_MS) { player.prepare(source) } }
        return player.stateFlow.value
    }

    private fun assertPlaysAndSeeks(player: VideoPlayer, what: String) {
        player.play()
        runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
        assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "$what: state ${player.stateFlow.value} after the playhead moved")
        player.seekTo(1_500L)
        runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it in 1_000L..2_900L } } }
        assertTrue(player.stateFlow.value !is VideoPlayerState.Error, "$what: ${player.stateFlow.value} after a seek")
    }

    @Test
    fun aMissingFileOnAServerArrivesAsErrorMediaException() {
        val web: RawServer = serve { _, _, out -> head(out, "HTTP/1.1 404 Not Found", "Content-Length: 0") }
        val state: VideoPlayerState = prepare(newPlayer(), VideoSource.Remote(url(web)))
        assertTrue(state is VideoPlayerState.Error, "a 404 settled on $state")
        val cause: Throwable = (state as VideoPlayerState.Error).cause
        assertTrue(cause is MediaException, "a 404 was $cause")
    }

    @Test
    fun aServerWithByteRangesPlaysAndSeeks() {
        val web: RawServer = serve { request, socket, out -> serveClip(request, socket, out, ranges = true) }
        val player: VideoPlayer = newPlayer()
        val state: VideoPlayerState = prepare(player, VideoSource.Remote(url(web)))
        assertTrue(state is VideoPlayerState.Ready, "a range server settled on $state (requests ${web.requests})")
        assertPlaysAndSeeks(player, "range server, requests ${web.requests}")
    }

    @Test
    fun aServerThatIgnoresByteRangesEndsTheLoadAtTheReadyTimeoutInsteadOfHanging() {
        val web: RawServer = serve { request, socket, out -> serveClip(request, socket, out, ranges = false) }
        val outcome: Throwable? = loadOutcome(url(web), readyTimeoutMs = 3_000L)
        assertTrue(
            outcome == null || outcome is JavaFxVideoPlayerException.LoadTimedOut || outcome is MediaException,
            "a load against a server without byte ranges ended with $outcome (requests ${web.requests})",
        )
    }

    @Test
    fun aRedirectToTheClipPlaysBecauseTheUrlIsPassedToJavaFxAsIs() {
        val web: RawServer = serve { request, socket, out ->
            if (request.path == "/clip.mp4") serveClip(request, socket, out, ranges = true) else head(out, "HTTP/1.1 302 Found", "Location: /clip.mp4", "Content-Length: 0")
        }
        val player: VideoPlayer = newPlayer()
        val state: VideoPlayerState = prepare(player, VideoSource.Remote(url(web, "/start")))
        assertTrue(state is VideoPlayerState.Ready, "a 302 to the clip settled on $state (requests ${web.requests})")
        player.play()
        runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
    }

    @Test
    fun aFaststartClipIsReadyBeforeTheLastBytesOfTheFileHaveArrived() {
        val gate = CountDownLatch(1)
        val web: RawServer = serve { request, socket, out -> serveClip(request, socket, out, ranges = true, tailGate = gate) }
        try {
            val outcome: Throwable? = loadOutcome(url(web), readyTimeoutMs = 8_000L)
            assertTrue(gate.count == 1L, "the gate opened by itself")
            assertEquals(null, outcome, "a faststart clip with its last 200 bytes withheld ended the load with $outcome (requests ${web.requests})")
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun aConnectionResetWhileTheClipIsLoadingEndsTheLoadWithinTheReadyTimeoutInsteadOfHanging() {
        // The moov box (the first 2700 bytes of the clip) is served whole; every response is then reset after 3400 bytes, inside the media data.
        val web: RawServer = serve { request, socket, out -> serveClip(request, socket, out, ranges = true, resetAtOffset = 3_400) }
        val outcome: Throwable? = loadOutcome(url(web), readyTimeoutMs = 3_000L)
        assertTrue(
            outcome is JavaFxVideoPlayerException.LoadTimedOut || outcome is MediaException,
            "a load whose connection was reset ended with $outcome (requests ${web.requests})",
        )
    }

    @Test
    fun aServerThatSendsAHundredBytesASecondEndsTheLoadAtItsReadyTimeoutAndLeavesTheFxThreadFree() {
        val stop = AtomicBoolean(false)
        val web: RawServer = serve { request, _, out ->
            head(out, "HTTP/1.1 200 OK", "Content-Type: video/mp4", "Content-Length: ${clip.size}")
            var sent = 0
            while (sent < clip.size && !stop.get()) {
                val n: Int = minOf(100, clip.size - sent)
                out.write(clip, sent, n)
                out.flush()
                sent += n
                // timing-hygiene-ignore: TH1 inject a fake peer that dribbles the body, one hundred bytes a second
                Thread.sleep(1_000L)
            }
        }
        val engine = JavaFxVideoEngine(readyTimeoutMs = 3_000L)
        var outcome: Throwable? = null
        val finished = AtomicBoolean(false)
        val loader = thread(name = "scout-ko-slow-load", isDaemon = true) {
            outcome = try {
                runBlocking { withTimeout(25_000L) { engine.load(VideoSource.Remote(url(web))) } }
                null
            } catch (e: Throwable) {
                e
            } finally {
                finished.set(true)
            }
        }
        // 300 rounds of at least 100 ms: the hang bound, counted in rounds instead of read off a clock.
        var rounds = 0
        while (loader.isAlive && rounds++ < 300) {
            assertFxThreadAnswers("while the load waited on a slow server")
            loader.join(100L)
        }
        stop.set(true)
        loader.join(5_000L)
        engine.release()
        assertTrue(finished.get(), "the load never ended against a server that dribbles the body")
        val ended: Throwable? = outcome
        assertTrue(
            ended is JavaFxVideoPlayerException.LoadTimedOut || ended is MediaException,
            "a load against a dribbling server did not fail with LoadTimedOut or MediaException: $ended",
        )
        assertFxThreadAnswers("after the load gave up")
    }

    @Test
    fun anHttpsUrlAgainstAServerThatOnlySpeaksPlainHttpEndsTheLoadWithinTheReadyTimeout() {
        val web: RawServer = serve { _, _, out -> head(out, "HTTP/1.1 400 Bad Request", "Content-Length: 0") }
        val outcome: Throwable? = loadOutcome("https://127.0.0.1:${web.port}/clip.mp4", readyTimeoutMs = 3_000L)
        assertTrue(
            outcome is JavaFxVideoPlayerException.LoadTimedOut || outcome is MediaException,
            "a failed TLS handshake ended the load with $outcome",
        )
    }

    /** The FX thread runs a task posted now within two seconds: the bound is the latch's own, no clock is read. */
    private fun assertFxThreadAnswers(when_: String) {
        val ran = CountDownLatch(1)
        Platform.runLater { ran.countDown() }
        assertTrue(ran.await(2L, TimeUnit.SECONDS), "the FX thread was blocked for more than 2 s $when_")
    }

    private companion object {
        const val HANG_MS: Long = 30_000L
        const val PREPARE_HANG_MS: Long = 45_000L
    }
}
