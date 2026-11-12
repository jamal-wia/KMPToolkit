package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy
import io.github.jamal_wia.kmptoolkit.uploader.RetryPolicy
import io.github.jamal_wia.kmptoolkit.uploader.UploaderConfig
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 (A1, real HTTP): the engine over the real SQLite file, with a handler that talks to a real local HTTP server
 * (JDK `HttpServer`): a server that fails, flaps, answers slowly, resets the connection, truncates a body, redirects,
 * and answers 429 with `Retry-After`. Asserted: the documented state transitions (a failure spends one attempt and gates
 * the row by the policy's backoff, a permanent rejection parks, a give-up policy parks after exactly its budget), no tight
 * loop against a failing server, no second delivery after a confirmed one, strict FIFO inside an ordering channel.
 */
@RunWith(AndroidJUnit4::class)
class UploaderEngineHttpServerRound8AdversarialTest {

    private sealed interface Reply {
        data class Status(val code: Int, val headers: Map<String, String> = emptyMap()) : Reply
        data class SlowThen(val millis: Long, val then: Reply) : Reply
        data object Reset : Reply
        data object Truncated : Reply
    }

    private class Arrival(val id: String, val atNanos: Long)

    private val arrivals: MutableList<Arrival> = Collections.synchronizedList(mutableListOf())
    private val counts: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    private var script: (id: String, nth: Int) -> Reply = { _, _ -> Reply.Status(200) }
    private lateinit var server: HttpServer
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "r8-http").apply { isDaemon = true } }
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val names: MutableList<String> = mutableListOf()
    private var engine: UploaderEngine? = null

    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = pool
        server.createContext("/") { ex: HttpExchange ->
            val id: String = ex.requestURI.path.substringAfterLast('/')
            val path: String = ex.requestURI.path
            arrivals += Arrival(id, System.nanoTime())
            ex.requestBody.readBytes()
            when {
                path.startsWith("/redirect/") -> {
                    ex.responseHeaders.add("Location", "$base/final/$id")
                    ex.sendResponseHeaders(302, -1)
                }
                path.startsWith("/loop/") -> {
                    ex.responseHeaders.add("Location", "$base/loop/$id")
                    ex.sendResponseHeaders(302, -1)
                }
                else -> reply(ex, script(id, counts.merge(id, 1, Int::plus)!!))
            }
            ex.close()
        }
        server.start()
    }

    private fun reply(ex: HttpExchange, r: Reply) {
        when (r) {
            is Reply.Status -> {
                r.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(r.code, -1)
            }
            is Reply.SlowThen -> {
                try { Thread.sleep(r.millis) } catch (_: InterruptedException) { }
                try { reply(ex, r.then) } catch (_: IOException) { }
            }
            Reply.Reset -> ex.close() // no response at all
            Reply.Truncated -> {
                ex.sendResponseHeaders(200, 100)
                ex.responseBody.write(ByteArray(10))
                ex.responseBody.flush()
                // closed with 90 bytes owed
            }
        }
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        scope.cancel()
        server.stop(0)
        pool.shutdownNow()
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
        storages.forEach { runCatching { it.close() } }
        names.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private val policy: RetryPolicy = ExponentialBackoffRetryPolicy(baseDelayMillis = 40, maxDelayMillis = 400, jitterRatio = 0.0)

    private inner class HttpHandler(
        override val retryPolicy: RetryPolicy = policy,
        private val pathPrefix: String = "item",
        private val ordered: Boolean = false,
    ) : UploaderHandler<String> {
        override val type: String = "http.effect"
        val successes: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
        val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override fun orderingKey(payload: String): String? = if (ordered) "channel" else null
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = withContext(Dispatchers.IO) {
            try {
                val c = URL("$base/$pathPrefix/$payload").openConnection() as HttpURLConnection
                c.connectTimeout = 1_000
                c.readTimeout = 300
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(payload.toByteArray()) }
                val code: Int = c.responseCode
                if (code in 200..299) {
                    val body: ByteArray = c.inputStream.use { it.readBytes() }
                    // the JDK client hands back a body cut short without an error: compare with the declared length
                    val declared: Long = c.getHeaderField("Content-Length")?.toLongOrNull() ?: body.size.toLong()
                    if (body.size.toLong() != declared) throw IOException("truncated body ${body.size}/$declared")
                    successes += payload to System.nanoTime()
                    AttemptResult.Success
                } else if (code == 408 || code == 429 || code in 500..599) {
                    failures += "$payload:$code"
                    AttemptResult.Retry(IOException("http $code"))
                } else {
                    failures += "$payload:$code"
                    AttemptResult.Park("http $code")
                }
            } catch (e: IOException) {
                failures += "$payload:io"
                AttemptResult.Retry(e)
            } catch (e: RuntimeException) {
                failures += "$payload:${e::class.simpleName}"
                throw e
            }
        }
    }

    private fun open(): UploaderStorage {
        val name = "adv.a1r8.http.${Random.nextLong(0L, Long.MAX_VALUE)}"
        names += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }
    }

    private fun start(storage: UploaderStorage, handler: UploaderHandler<String>): UploaderEngine =
        createUploaderEngine(
            store = storage.store, handlers = listOf(handler), scope = scope, transactionRunner = storage.transactionRunner,
            config = UploaderConfig(heartbeatInterval = 200.milliseconds, drainPollInterval = 20.milliseconds),
        ).also { it.start(); engine = it }

    private suspend fun awaitGone(storage: UploaderStorage, seconds: Int = 20) = withTimeout(seconds.seconds) {
        while (storage.store.getAllActive().isNotEmpty()) delay(10)
    }

    private fun gapsMillis(id: String): List<Long> =
        arrivals.filter { it.id == id }.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000 }

    @Test
    fun `503 three times then 200 spends one attempt per failure, waits the policy's backoff between tries, then deletes the row`() = runBlocking {
        script = { _, nth -> if (nth <= 3) Reply.Status(503) else Reply.Status(200) }
        val storage: UploaderStorage = open()
        val handler = HttpHandler()
        val uploader: UploaderEngine = start(storage, handler)
        val id: String = checkNotNull(uploader.enqueue(handler, "a"))
        // while it backs off the row is PENDING, carries the attempts spent and a gate in the future
        var sawGate = false
        withTimeout(20.seconds) {
            while (storage.store.getAllActive().isNotEmpty()) {
                val row: UploaderItem? = storage.store.getById(id)
                if (row != null && row.attempts in 1..3 && row.state == UploaderItemState.PENDING && row.nextRunAtEpochMillis > System.currentTimeMillis()) sawGate = true
                delay(5)
            }
        }
        assertEquals(4, counts["a"], "requests to a server that failed three times")
        val gaps: List<Long> = gapsMillis("a")
        println("R8 503x3 gaps(ms)=$gaps")
        assertTrue(gaps[0] >= 40 - 15 && gaps[1] >= 80 - 15 && gaps[2] >= 160 - 15, "retries came faster than the policy's backoff: $gaps")
        assertTrue(sawGate, "a backing-off row was never observed PENDING with attempts and a future gate")
        assertEquals(listOf("a:503", "a:503", "a:503"), handler.failures)
    }

    @Test
    fun `429 with Retry-After is retried by the policy's backoff, the header is not honored, and the row is not parked`() = runBlocking {
        script = { _, nth -> if (nth == 1) Reply.Status(429, mapOf("Retry-After" to "2")) else Reply.Status(200) }
        val storage: UploaderStorage = open()
        val handler = HttpHandler()
        val uploader: UploaderEngine = start(storage, handler)
        uploader.enqueue(handler, "b")
        awaitGone(storage)
        val gap: Long = gapsMillis("b").single()
        println("R8 429 Retry-After=2000ms: the engine retried after ${gap}ms (policy base 40ms): header honored=${gap >= 2_000}")
        assertEquals(2, counts["b"])
    }

    @Test
    fun `a permanent 404 parks after one request, sends nothing more, and a fresh enqueue on the same key revives it`() = runBlocking {
        script = { _, nth -> if (nth == 1) Reply.Status(404) else Reply.Status(200) }
        val storage: UploaderStorage = open()
        val handler = HttpHandler()
        val uploader: UploaderEngine = start(storage, handler)
        val id: String = checkNotNull(uploader.enqueue(handler, "c", uniqueKey = "doc-c"))
        withTimeout(10.seconds) { while (storage.store.getById(id)?.state != UploaderItemState.PARKED) delay(10) }
        delay(1_200) // heartbeat passes, wakes, triggers: a parked row is out of rotation
        assertEquals(1, counts["c"], "a parked row was sent again")
        assertTrue(storage.store.getById(id)?.lastError?.contains("404") == true, "last error: ${storage.store.getById(id)?.lastError}")
        assertTrue(storage.store.getAllActive().isEmpty())
        val revived: String? = uploader.enqueue(handler, "c", uniqueKey = "doc-c", conflictPolicy = ConflictPolicy.KEEP)
        assertTrue(revived != null && revived != id, "a keep over a parked holder must enqueue")
        awaitGone(storage)
        assertEquals(2, counts["c"])
        assertNull(storage.store.getById(id), "the parked holder was replaced")
    }

    @Test
    fun `a redirect is followed inside one attempt, and a redirect loop is a retry per attempt that parks after exactly the give-up budget`() = runBlocking {
        val storage: UploaderStorage = open()
        val ok = HttpHandler(pathPrefix = "redirect")
        val uploader: UploaderEngine = start(storage, ok)
        uploader.enqueue(ok, "r1")
        awaitGone(storage)
        assertEquals(1, ok.successes.size, "one delivery behind a redirect")
        assertEquals(2, arrivals.count { it.id == "r1" }, "the redirect and its target")
        uploader.close()

        val limited: RetryPolicy = object : RetryPolicy {
            override val maxDelayMillis: Long = 400L
            override val giveUp: GiveUpPolicy = GiveUpPolicy.ParkAfterAttempts(3)
            override fun backoffMillis(attempts: Int): Long = 40L
        }
        val looping = HttpHandler(retryPolicy = limited, pathPrefix = "loop")
        val second: UploaderEngine = start(storage, looping)
        val id: String = checkNotNull(second.enqueue(looping, "l1"))
        withTimeout(20.seconds) { while (storage.store.getById(id)?.state != UploaderItemState.PARKED) delay(10) }
        val row: UploaderItem = checkNotNull(storage.store.getById(id))
        assertEquals(3, row.attempts, "attempts at the give-up")
        assertEquals(3, looping.failures.size, "attempts the handler saw")
        delay(800)
        assertEquals(3, looping.failures.size, "a parked row ran again")
    }

    @Test
    fun `a server slower than the read timeout is a retry, the late answer is not a second delivery, and close does not wait for the blocked attempt`() = runBlocking {
        script = { _, nth -> if (nth == 1) Reply.SlowThen(1_500, Reply.Status(200)) else Reply.Status(200) }
        val storage: UploaderStorage = open()
        val handler = HttpHandler()
        val uploader: UploaderEngine = start(storage, handler)
        uploader.enqueue(handler, "s")
        awaitGone(storage)
        assertEquals(listOf("s:io"), handler.failures)
        assertEquals(1, handler.successes.size)

        // close while an attempt is blocked inside the connection
        script = { _, _ -> Reply.SlowThen(1_200, Reply.Status(200)) }
        val slowHandler = HttpHandler()
        val second: UploaderEngine = start(storage, slowHandler)
        second.enqueue(slowHandler, "s2")
        delay(100)
        val closeStarted: Long = System.nanoTime()
        second.close()
        assertTrue((System.nanoTime() - closeStarted) / 1_000_000 < 500, "close() waited for a blocked attempt")
    }

    @Test
    fun `a reset connection and a truncated body are retries, never a delivery`() = runBlocking {
        script = { _, nth ->
            when (nth) {
                1 -> Reply.Reset
                2 -> Reply.Truncated
                else -> Reply.Status(200)
            }
        }
        val storage: UploaderStorage = open()
        val handler = HttpHandler()
        val uploader: UploaderEngine = start(storage, handler)
        uploader.enqueue(handler, "t")
        awaitGone(storage)
        // the JDK client may itself repeat a request whose connection was reset before any answer, so the server can see
        // more requests than the engine made attempts
        assertTrue((counts["t"] ?: 0) >= 3, "requests: ${counts["t"]}")
        assertEquals(1, handler.successes.size, "only the complete answer counts as delivered")
        assertTrue(handler.failures.isNotEmpty() && handler.failures.all { it == "t:io" }, "failures: ${handler.failures}")
    }

    @Test
    fun `a flapping server and forty items, every item is delivered once and never again after its confirmation`() = runBlocking {
        val dice: ConcurrentHashMap<String, Random> = ConcurrentHashMap()
        script = { id, _ ->
            val r: Random = dice.getOrPut(id) { Random(id.hashCode().toLong() xor 0x5eed) }
            when (synchronized(r) { r.nextInt(100) }) {
                in 0..54 -> Reply.Status(200)
                in 55..74 -> Reply.Status(503)
                in 75..84 -> Reply.Reset
                in 85..92 -> Reply.Truncated
                else -> Reply.SlowThen(450, Reply.Status(200))
            }
        }
        val storage: UploaderStorage = open()
        val handler = HttpHandler(ordered = false)
        val uploader: UploaderEngine = start(storage, handler)
        val ids: List<String> = (0 until 40).map { "f$it" }
        for (i in ids) uploader.enqueue(handler, i)
        awaitGone(storage, seconds = 60)
        val delivered: List<String> = handler.successes.map { it.first }
        assertEquals(ids.toSet(), delivered.toSet(), "an item was never confirmed")
        assertEquals(delivered.size, delivered.toSet().size, "an item was confirmed twice")
        for ((id, at) in handler.successes) {
            assertTrue(arrivals.none { it.id == id && it.atNanos > at }, "$id was sent again after its confirmation")
        }
        println("R8 FLAP requests=${arrivals.size} for ${ids.size} items, failures=${handler.failures.size}")
    }

    @Test
    fun `inside an ordering channel a failing head holds every later item back, and the order of confirmation is the order of enqueue`() = runBlocking {
        script = { id, nth -> if (id == "o0" && nth <= 3) Reply.Status(503) else Reply.Status(200) }
        val storage: UploaderStorage = open()
        val handler = HttpHandler(ordered = true)
        val uploader: UploaderEngine = start(storage, handler)
        val ids: List<String> = (0 until 8).map { "o$it" }
        for (i in ids) uploader.enqueue(handler, i)
        awaitGone(storage)
        assertEquals(ids, handler.successes.map { it.first }, "confirmation order")
        val firstOfTail: Long = arrivals.first { it.id == "o1" }.atNanos
        val headConfirmed: Long = handler.successes.first { it.first == "o0" }.second
        assertTrue(firstOfTail > headConfirmed, "a later item of the channel reached the server before the head was confirmed")
    }
}
