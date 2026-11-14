package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javafx.scene.media.MediaException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * D2 scout docs batch (executor kq): the corrected sentences of `03-guide.md` ("Sources on desktop", "Errors you can see") and
 * `05-platform-notes.md` ("Formats", "What an unsupported source does") about remote sources that fail, pinned on real macOS media
 * against a local server on 127.0.0.1 (no external network). The oracle is the documented text, not the engine:
 *
 * - a target JavaFX can reach but cannot open (an HTTP 404) is a `MediaException`, and on macOS its type is `MEDIA_UNAVAILABLE`,
 *   not `MEDIA_INACCESSIBLE`, which is the type of a refused connection;
 * - a remote source that never answers usefully (an `https` URL whose TLS handshake fails, a connection reset while the clip loads,
 *   a server that ignores byte ranges) reports nothing on macOS, and the engine fails that load with `LoadTimedOut`: the engine's
 *   own bound, so `prepare` never hangs. The ready timeout here is 3 s (15 s for the 404, which macOS reports after about 5 s), the production default of 30 s is not waited for;
 * - the server must honour byte ranges: a server that answers a `Range` request with the whole file (`200`) never gets the source
 *   ready, while the same clip served with `206` does (the control that keeps the other tests from passing on a broken server).
 *
 * Characterisation tests of the platform's behaviour behind the engine's bound (D-298): they assert no elapsed wall time, only the
 * kind of failure; every wait is bounded by the ready timeout plus a hang bound that fails with its own message. Real media, so the
 * class runs only under the exclusive slot. The three failure sentences are macOS statements, so the other systems skip them.
 */
@OptIn(ToolkitInternalApi::class)
class RemoteSourceFailuresD2ScoutDocsRealMediaTest {

    /** A one-request-per-connection HTTP/1.1 server on the loopback interface; [handler] writes the whole response. */
    private class LocalServer(private val handler: (Map<String, String>, Socket, OutputStream) -> Unit) {
        private val socket: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val open: MutableList<Socket> = CopyOnWriteArrayList()
        private val running: AtomicBoolean = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        init {
            thread(name = "scout-kq-http-accept", isDaemon = true) {
                while (running.get()) {
                    val client: Socket = try {
                        socket.accept()
                    } catch (e: Exception) {
                        return@thread
                    }
                    open += client
                    thread(name = "scout-kq-http-conn", isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                input.readLine() ?: return
                val headers: MutableMap<String, String> = linkedMapOf()
                while (true) {
                    val line: String = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon: Int = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                handler(headers, client, client.getOutputStream())
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
    private var server: LocalServer? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        clip = TestClip::class.java.classLoader.getResourceAsStream(TestClip.ASSET)!!.use { it.readBytes() }
    }

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    private fun assumeMac() {
        Assume.assumeTrue("the sentence states a macOS behaviour", System.getProperty("os.name").startsWith("Mac"))
    }

    private fun serve(handler: (Map<String, String>, Socket, OutputStream) -> Unit): LocalServer =
        LocalServer(handler).also { server = it }

    private fun head(out: OutputStream, status: String, vararg lines: String) {
        out.write((listOf(status) + lines + listOf("Connection: close", "", "")).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Answers a GET of the clip like a static file server: with `206` for a `Range` request when [ranges] is set, with the whole file
     * and `200` otherwise. [resetAtOffset] cuts the connection with a reset once the response has delivered the file up to that
     * offset (a response that ends before it is served whole).
     */
    private fun serveClip(headers: Map<String, String>, socket: Socket, out: OutputStream, ranges: Boolean, resetAtOffset: Int? = null) {
        val match: MatchResult? = headers["range"]?.takeIf { ranges }?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it) }
        val from: Int = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val to: Int = minOf(match?.groupValues?.get(2)?.toIntOrNull() ?: (clip.size - 1), clip.size - 1)
        if (match != null) {
            head(out, "HTTP/1.1 206 Partial Content", "Content-Type: video/mp4", "Accept-Ranges: bytes", "Content-Range: bytes $from-$to/${clip.size}", "Content-Length: ${to - from + 1}")
        } else {
            head(out, "HTTP/1.1 200 OK", "Content-Type: video/mp4", "Accept-Ranges: ${if (ranges) "bytes" else "none"}", "Content-Length: ${clip.size}")
        }
        if (resetAtOffset != null && to >= resetAtOffset) {
            out.write(clip, from, maxOf(0, resetAtOffset - from))
            out.flush()
            // A reset, not an orderly close: what a dropped connection looks like.
            socket.setSoLinger(true, 0)
            socket.close()
            return
        }
        out.write(clip, from, to - from + 1)
        out.flush()
    }

    /** Loads [url] through the engine with the given ready timeout and returns what the load ended with, `null` for a success. */
    private fun loadOutcome(url: String, readyTimeoutMs: Long = READY_TIMEOUT_MS): Throwable? {
        val engine = JavaFxVideoEngine(readyTimeoutMs = readyTimeoutMs)
        return try {
            runBlocking { withTimeout(readyTimeoutMs + HANG_MS) { engine.load(VideoSource.Remote(url)) } }
            null
        } catch (e: Throwable) {
            e
        } finally {
            engine.release()
        }
    }

    private fun url(web: LocalServer): String = "http://127.0.0.1:${web.port}/clip.mp4"

    @Test
    fun anHttp404IsAMediaExceptionOfTypeMediaUnavailableOnMacOs() {
        assumeMac()
        val web: LocalServer = serve { _, _, out -> head(out, "HTTP/1.1 404 Not Found", "Content-Length: 0") }
        val outcome: Throwable? = loadOutcome(url(web), NOT_FOUND_READY_TIMEOUT_MS)
        assertTrue(outcome is MediaException, "a 404 was $outcome")
        assertEquals(MediaException.Type.MEDIA_UNAVAILABLE, outcome.type, "the type of a 404")
    }

    @Test
    fun anHttpsUrlAgainstAServerThatOnlySpeaksPlainHttpEndsInLoadTimedOutOnMacOs() {
        assumeMac()
        val web: LocalServer = serve { _, _, out -> head(out, "HTTP/1.1 400 Bad Request", "Content-Length: 0") }
        val outcome: Throwable? = loadOutcome("https://127.0.0.1:${web.port}/clip.mp4")
        assertTrue(outcome is JavaFxVideoPlayerException.LoadTimedOut, "a failed TLS handshake ended the load with $outcome")
        assertEquals(READY_TIMEOUT_MS, outcome.timeoutMs, "the bound the engine waited")
    }

    @Test
    fun aConnectionResetWhileTheClipIsLoadingEndsInLoadTimedOutOnMacOs() {
        assumeMac()
        // The moov box (the first 2700 bytes of the clip) is served whole; every response is then reset after 3400 bytes, inside the media data.
        val web: LocalServer = serve { headers, socket, out -> serveClip(headers, socket, out, ranges = true, resetAtOffset = 3_400) }
        val outcome: Throwable? = loadOutcome(url(web))
        assertTrue(outcome is JavaFxVideoPlayerException.LoadTimedOut, "a load whose connection was reset ended with $outcome")
    }

    @Test
    fun aServerThatIgnoresByteRangesEndsInLoadTimedOutOnMacOs() {
        assumeMac()
        val web: LocalServer = serve { headers, socket, out -> serveClip(headers, socket, out, ranges = false) }
        val outcome: Throwable? = loadOutcome(url(web))
        assertTrue(outcome is JavaFxVideoPlayerException.LoadTimedOut, "a server that answers a range request with 200 ended the load with $outcome")
    }

    @Test
    fun theSameClipServedWithByteRangesBecomesReadyWithinTheSameTimeout() {
        assumeMac()
        val web: LocalServer = serve { headers, socket, out -> serveClip(headers, socket, out, ranges = true) }
        val outcome: Throwable? = loadOutcome(url(web))
        assertEquals(null, outcome, "the control: a range server serving the whole clip ended the load with $outcome")
    }

    private companion object {
        /** The ready timeout of every load here: short, so that no failure test waits the production 30 s. */
        const val READY_TIMEOUT_MS: Long = 3_000L

        /**
         * The ready timeout of the 404 load: JavaFX on macOS takes about 5 s to report a 404, so 3 s would end it in `LoadTimedOut`
         * (seen). Not an assertion on the duration: only the failure kind is checked, and a slower machine just waits longer.
         */
        const val NOT_FOUND_READY_TIMEOUT_MS: Long = 15_000L

        /** On top of the ready timeout: the hang bound that fails with its own message instead of hanging Gradle. */
        const val HANG_MS: Long = 20_000L
    }
}
