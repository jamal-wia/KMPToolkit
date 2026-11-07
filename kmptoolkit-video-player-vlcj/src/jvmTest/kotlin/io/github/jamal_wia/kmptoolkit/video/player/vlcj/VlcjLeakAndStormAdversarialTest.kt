package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Adversarial resource and storm tests for [VlcjVideoEngine] against [FakeVlcRuntime]: repeated
 * create/load/release cycles, temporary asset copies of jar-packed sources, an engine dropped
 * without release, and concurrent release while VLC's threads deliver events. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLeakAndStormAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private val listener = RecordingListener()
    private val runtimeCreations = AtomicInteger()
    private val tempFiles: MutableList<File> = CopyOnWriteArrayList()
    private val loaders: MutableList<URLClassLoader> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
        loaders.forEach { it.close() }
        tempFiles.forEach { it.delete() }
    }

    private fun newEngine(
        discover: () -> Boolean = { true },
        classLoader: ClassLoader? = null,
    ): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = discover,
        classLoader = classLoader,
        createRuntime = { runtimeCreations.incrementAndGet(); runtime },
    ).also { it.setListener(listener) }

    private fun teardownThreads(): Int = Thread.getAllStackTraces().keys.count {
        it.name == "kmptoolkit-vlcj-teardown" && it.isAlive
    }

    private fun awaitTrue(timeoutMs: Long = 10_000L, message: () -> String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms: ${message()}")
            Thread.sleep(20L)
        }
    }

    private suspend fun loadClip(engine: VlcjVideoEngine) {
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
    }

    /** A jar with one asset, behind a loader that sees nothing else: the asset URL is `jar:` not `file:`. */
    private fun jarLoader(extension: String): URLClassLoader {
        val jar: File = Files.createTempFile("d2-assets-", ".jar").toFile().also { tempFiles += it }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("assets/clip.$extension"))
            out.write(ByteArray(2_048) { it.toByte() })
            out.closeEntry()
        }
        return URLClassLoader(arrayOf(jar.toURI().toURL()), null).also { loaders += it }
    }

    private fun copiesOnDisk(extension: String): List<File> =
        File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("kmptoolkit-video-") && f.name.endsWith(".$extension") }
            .orEmpty().toList()

    // D2-01 ---------------------------------------------------------------------------------------

    @Test
    fun `a hundred load and release cycles free every native player once and the instance at dispose`() = runBlocking<Unit> {
        val baseline: Int = teardownThreads()
        val engine: VlcjVideoEngine = newEngine()
        repeat(100) {
            loadClip(engine)
            engine.release()
        }
        engine.dispose()

        awaitTrue(message = { "players=${runtime.players.size} released=${runtime.released}" }) {
            runtime.players.size == 100 && runtime.released == 1 &&
                runtime.players.all { p -> p.calls.count { it == "release" } == 1 }
        }
        runtime.players.forEachIndexed { index, p ->
            assertEquals(1, p.calls.count { it == "stop" }, "player $index stopped once")
            assertTrue(p.calls.indexOf("stop") < p.calls.indexOf("release"), "player $index stop before release")
            assertEquals("release", p.calls.last(), "nothing is called on player $index after its release")
        }
        val instance: Int = runtime.log.indexOfFirst { it.startsWith("runtime.release@") }
        val lastPlayerRelease: Int = runtime.log.indexOfLast { it.startsWith("release@") }
        assertTrue(lastPlayerRelease < instance, "the instance is freed after every player")
        assertEquals(1, runtimeCreations.get(), "one libvlc instance for the engine")
        // The teardown thread idles out after its keep-alive (5 s).
        awaitTrue(15_000L, message = { "teardown threads ${teardownThreads()} > baseline $baseline" }) { teardownThreads() <= baseline }
    }

    @Test
    fun `twenty five engines each loaded twice and disposed leave no player or instance behind and no teardown thread`() = runBlocking<Unit> {
        val baseline: Int = teardownThreads()
        repeat(25) {
            val engine: VlcjVideoEngine = newEngine()
            loadClip(engine)
            loadClip(engine) // closes the first session
            engine.dispose()
        }
        awaitTrue(message = { "players=${runtime.players.size} released=${runtime.released}" }) {
            runtime.players.size == 50 && runtime.released == 25 &&
                runtime.players.all { p -> p.calls.count { it == "release" } == 1 }
        }
        awaitTrue(15_000L, message = { "teardown threads ${teardownThreads()} > baseline $baseline" }) { teardownThreads() <= baseline }
    }

    // D2-02 ---------------------------------------------------------------------------------------

    @Test
    fun `fifty jar asset loads and releases leave no temporary copy behind`() = runBlocking<Unit> {
        val ext = "d2leakfifty"
        val loader: URLClassLoader = jarLoader(ext)
        val liveBefore: Int = AssetCopies.liveCount
        val engine: VlcjVideoEngine = newEngine(classLoader = loader)
        repeat(50) {
            withTimeout(5_000L) { engine.load(VideoSource.Asset("assets/clip.$ext")) }
            assertEquals(1, copiesOnDisk(ext).size, "exactly the live session's copy exists")
            engine.release()
        }
        engine.dispose()
        awaitTrue(message = { "copies=${copiesOnDisk(ext)} live=${AssetCopies.liveCount}" }) {
            copiesOnDisk(ext).isEmpty() && AssetCopies.liveCount == liveBefore
        }
    }

    @Test
    fun `jar asset loads that are replaced, fail or are cancelled leave no temporary copy behind`() = runBlocking<Unit> {
        val ext = "d2leakmixed"
        val loader: URLClassLoader = jarLoader(ext)
        val liveBefore: Int = AssetCopies.liveCount
        val asset = VideoSource.Asset("assets/clip.$ext")

        // replaced without a release in between
        val replacing: VlcjVideoEngine = newEngine(classLoader = loader)
        repeat(20) { withTimeout(5_000L) { replacing.load(asset) } }
        replacing.release()

        // parser fails / times out / finds no track
        for (status: VlcParseStatus in listOf(VlcParseStatus.FAILED, VlcParseStatus.TIMEOUT)) {
            runtime.parseResult = status
            val engine: VlcjVideoEngine = newEngine(classLoader = loader)
            repeat(10) { runCatching { withTimeout(5_000L) { engine.load(asset) } } }
            engine.dispose()
        }
        runtime.parseResult = VlcParseStatus.DONE
        runtime.parsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 0, videoSize = null)
        newEngine(classLoader = loader).let { engine ->
            repeat(10) { runCatching { withTimeout(5_000L) { engine.load(asset) } } }
            engine.dispose()
        }
        runtime.parsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 1, videoSize = null)

        // libvlc not found
        newEngine(discover = { false }, classLoader = loader).let { engine ->
            repeat(10) { runCatching { withTimeout(5_000L) { engine.load(asset) } } }
            engine.dispose()
        }

        // cancelled while the parser never answers
        runtime.parseResult = null
        val parked: VlcjVideoEngine = newEngine(classLoader = loader)
        repeat(15) { round: Int ->
            val before: Int = runtime.players.size
            val job = launch(Dispatchers.Default) { parked.load(asset) }
            awaitTrue(message = { "round $round: the load never reached the native player" }) { runtime.players.size > before }
            job.cancelAndJoin()
        }
        parked.dispose()

        awaitTrue(message = { "copies=${copiesOnDisk(ext)} live=${AssetCopies.liveCount}" }) {
            copiesOnDisk(ext).isEmpty() && AssetCopies.liveCount == liveBefore
        }
        awaitTrue(message = { "players not all released" }) { runtime.players.all { p -> p.calls.count { it == "release" } == 1 } }
    }

    // D2-03 ---------------------------------------------------------------------------------------

    private fun loadJarAssetAndDrop(ext: String): WeakReference<VlcjVideoEngine> {
        val engine: VlcjVideoEngine = newEngine(classLoader = jarLoader(ext))
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.Asset("assets/clip.$ext")) } }
        return WeakReference(engine)
    }

    @Test
    fun `an engine dropped after a jar asset load frees the native player and deletes the copy (GC dependent)`() {
        val ext = "d2leakdrop"
        val liveBefore: Int = AssetCopies.liveCount
        val reference: WeakReference<VlcjVideoEngine> = loadJarAssetAndDrop(ext)
        assertEquals(1, copiesOnDisk(ext).size)
        var rounds = 0
        while (reference.get() != null && rounds < 100) {
            System.gc()
            Thread.sleep(50L)
            rounds++
        }
        assertTrue(reference.get() == null, "the engine was never collected after $rounds rounds (not a defect by itself)")
        awaitTrue(message = { "player release=${runtime.players.map { it.calls }} copies=${copiesOnDisk(ext)}" }) {
            runtime.players.single().calls.count { it == "release" } == 1 && runtime.released == 1 &&
                copiesOnDisk(ext).isEmpty() && AssetCopies.liveCount == liveBefore
        }
    }

    // D2-04 ---------------------------------------------------------------------------------------

    @Test
    fun `a seek target is not overwritten by two hundred stale clock readings from the event thread`() = runBlocking<Unit> {
        val clock = AtomicInteger()
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = { runtime },
            nanoTime = { 1_000_000_000L + clock.get() }, // frozen: the hold never expires on wall time
        ).also { it.setListener(listener) }
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()

        for (round: Int in 0 until 20) {
            val target: Long = 60_000L + round * 1_000L
            player.playhead = 5_000L
            engine.seekTo(target)
            player.playhead = 5_000L // libvlc applies set_time asynchronously: the clock still reads the old time
            val lowest = AtomicInteger(Int.MAX_VALUE)
            val done = AtomicBoolean(false)
            val sampler = thread {
                while (!done.get()) lowest.accumulateAndGet(engine.positionMs().toInt()) { a, b -> minOf(a, b) }
            }
            repeat(200) { index: Int -> player.fire { timeChanged(5_000L + (index % 7) * 1_000L) } }
            done.set(true)
            sampler.join(5_000L)
            assertTrue(lowest.get().toLong() >= target, "round $round: the playhead dipped to ${lowest.get()} below target $target")
            // libvlc reports a time near the target: the clock takes over.
            player.playhead = target + 300L
            player.fire { timeChanged(target + 300L) }
            assertEquals(target + 300L, engine.positionMs(), "round $round: the clock takes over after the target settled")
        }
        engine.dispose()
    }

    // D2-05 ---------------------------------------------------------------------------------------

    @Test
    fun `eight threads releasing and disposing while events fire free once and deliver nothing after`() = runBlocking<Unit> {
        repeat(15) { round: Int ->
            val before: Int = runtime.players.size
            val engine: VlcjVideoEngine = newEngine()
            loadClip(engine)
            engine.start()
            val player: FakeVlcPlayer = runtime.players[before]
            player.fire { playing() }
            runtime.drainTasks()

            val allReturned = AtomicBoolean(false)
            val late = AtomicInteger()
            val localListener = object : io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener {
                override fun onCompleted() { if (allReturned.get()) late.incrementAndGet() }
                override fun onFailed(cause: Throwable) { if (allReturned.get()) late.incrementAndGet() }
                override fun onBufferingChanged(isBuffering: Boolean) { if (allReturned.get()) late.incrementAndGet() }
                override fun onVideoSizeChanged(size: io.github.jamal_wia.kmptoolkit.video.player.VideoSize?) { if (allReturned.get()) late.incrementAndGet() }
            }
            engine.setListener(localListener)

            val stop = AtomicBoolean(false)
            val storm = thread {
                var i = 0
                while (!stop.get() && i < 2_000) {
                    runCatching {
                        when (i % 5) {
                            0 -> player.fire { timeChanged(i.toLong()) }
                            1 -> player.fire { buffering(if (i % 2 == 0) 10f else 100f) }
                            2 -> player.displayFrame(4, 4, 0x112233)
                            3 -> player.fire { finished() }
                            else -> player.fire { error() }
                        }
                    }
                    i++
                }
            }
            val gate = CountDownLatch(1)
            val callers = List(8) { n ->
                thread {
                    gate.await(5, TimeUnit.SECONDS)
                    engine.release()
                    if (n % 2 == 0) engine.dispose()
                }
            }
            gate.countDown()
            callers.forEach { it.join(10_000L) }
            assertTrue(callers.none { it.isAlive }, "round $round: a releasing thread hung")
            allReturned.set(true)
            Thread.sleep(30L) // events already queued behind the release get their chance to leak
            stop.set(true)
            storm.join(10_000L)
            assertTrue(!storm.isAlive, "round $round: the event storm hung")
            engine.dispose()

            awaitTrue(message = { "round $round: ${player.calls}" }) {
                player.calls.count { it == "release" } == 1 && player.calls.last() == "release"
            }
            assertEquals(0, late.get(), "round $round: callbacks reached the listener after every release returned")
            assertEquals(null, engine.frames.value, "round $round: a frame survived the release")
        }
    }

    @Test
    fun `every event of a replaced source is dropped across thirty reloads`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        repeat(30) { round: Int ->
            val old: FakeVlcPlayer = runtime.player
            loadClip(engine) // replaces the session of [old]
            val current: FakeVlcPlayer = runtime.player
            current.fire { lengthChanged(2_000L) }
            val eventsBefore: Int = listener.events.size
            old.fire { timeChanged(999L) }
            old.fire { lengthChanged(77_000L) }
            old.fire { buffering(10f) }
            old.fire { finished() }
            old.fire { error() }
            old.displayFrame(4, 4, 0x445566)
            old.fire { bufferFormat(320, 240) }
            old.fire { playing() }
            runtime.drainTasks()
            assertEquals(eventsBefore, listener.events.size, "round $round: the replaced source reached the listener: ${listener.events.drop(eventsBefore)}")
            assertEquals(null, engine.frames.value, "round $round: a frame of the replaced source was published")
            assertEquals(2_000L, engine.durationMs(), "round $round: the replaced source changed the duration")
            assertEquals(0L, engine.positionMs(), "round $round: the replaced source moved the playhead")
            assertTrue(old.calls.none { it == "play" || it.startsWith("setTime") }, "round $round: ${old.calls}")
        }
        engine.dispose()
    }

    // D2-08 ---------------------------------------------------------------------------------------

    @Test
    fun `sixteen concurrent prepares without VLC all settle on the typed error`() = runBlocking<Unit> {
        val player = createVideoPlayer(
            engine = newEngine(discover = { false }),
            config = io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig(),
            coroutineContext = Dispatchers.Default,
        )
        withTimeout(20_000L) {
            val calls = List(16) { async(Dispatchers.Default, CoroutineStart.DEFAULT) { player.prepare(VideoSource.File(TestClip.file.path)) } }
            calls.forEach { it.await() }
        }
        val state = player.stateFlow.value
        assertTrue(state is VideoPlayerState.Error && state.cause is VlcUnavailableException, "state: $state")
        player.release()
    }

    @Test
    fun `sixteen players on sixteen threads without VLC or with a discovery that throws settle on an error`() = runBlocking<Unit> {
        withTimeout(30_000L) {
            val results = List(16) { n ->
                async(Dispatchers.Default) {
                    val discover: () -> Boolean = if (n % 2 == 0) ({ false }) else ({ throw UnsatisfiedLinkError("jna $n") })
                    val player = createVideoPlayer(
                        engine = newEngine(discover = discover),
                        config = io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig(),
                        coroutineContext = Dispatchers.Default,
                    )
                    player.prepare(VideoSource.File(TestClip.file.path))
                    val state = player.stateFlow.value
                    player.release()
                    n to state
                }
            }.map { it.await() }
            results.forEach { (n: Int, state) ->
                assertTrue(state is VideoPlayerState.Error, "player $n: $state")
                if (n % 2 == 0) assertTrue(state.cause is VlcUnavailableException, "player $n: ${state.cause}")
            }
        }
    }

    // player level: release mid-load, storms ------------------------------------------------------

    @Test
    fun `twenty releases while the parser never answers free every native player and return`() = runBlocking<Unit> {
        runtime.parseResult = null
        repeat(20) { round: Int ->
            val before: Int = runtime.players.size
            val player = createVideoPlayer(
                engine = newEngine(),
                config = io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig(),
                coroutineContext = Dispatchers.Default,
            )
            val prepare = launch(Dispatchers.Default) { player.prepare(VideoSource.File(TestClip.file.path)) }
            awaitTrue(message = { "round $round: prepare never reached the native player" }) { runtime.players.size > before }
            withTimeout(10_000L) {
                player.release()
                prepare.join()
            }
            awaitTrue(message = { "round $round: ${runtime.players[before].calls}" }) {
                runtime.players[before].calls.count { it == "release" } == 1
            }
        }
        awaitTrue(message = { "runtime released=${runtime.released}" }) { runtime.released == 20 }
    }

    @Test
    fun `a transport storm from eight threads with events, then release, calls nothing after the release`() = runBlocking<Unit> {
        val player = createVideoPlayer(
            engine = newEngine(),
            config = io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig(positionUpdateIntervalMs = 5L),
            coroutineContext = Dispatchers.Default,
        )
        withTimeout(10_000L) { player.prepare(VideoSource.File(TestClip.file.path)) }
        val native: FakeVlcPlayer = runtime.player
        player.play()
        native.fire { playing() }
        val stop = AtomicBoolean(false)
        val events = thread {
            var i = 0
            while (!stop.get() && i < 5_000) {
                runCatching { native.fire { timeChanged((i % 2_000).toLong()) } }
                i++
            }
        }
        val workers = List(8) { n ->
            thread {
                repeat(150) { i: Int ->
                    when ((i + n) % 6) {
                        0 -> player.seekTo(((i * 37) % 2_000).toLong())
                        1 -> player.pause()
                        2 -> player.play()
                        3 -> player.setVolume((i % 10) / 10f)
                        4 -> player.setPlaybackSpeed(0.5f + (i % 4) * 0.5f)
                        else -> player.seekForward(500L)
                    }
                }
            }
        }
        workers.forEach { it.join(20_000L) }
        assertTrue(workers.none { it.isAlive }, "a transport caller hung")
        player.release()
        stop.set(true)
        events.join(10_000L)
        awaitTrue(message = { "calls: ${native.calls.takeLast(5)}" }) { native.calls.count { it == "release" } == 1 }
        Thread.sleep(100L)
        assertEquals("release", native.calls.last(), "a native call reached the player after its release")
        awaitTrue(message = { "runtime released=${runtime.released}" }) { runtime.released == 1 }
    }

    @Test
    fun `an engine disposed from inside the completion callback frees everything once and refuses a later load`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        listener.reaction = { event -> if (event == "completed") engine.dispose() }
        player.fire { finished() }
        awaitTrue(message = { "calls ${player.calls} released ${runtime.released}" }) {
            player.calls.count { it == "release" } == 1 && runtime.released == 1
        }
        val failure: Throwable? = runCatching { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }.exceptionOrNull()
        assertTrue(failure != null, "a load after dispose must fail")
        assertEquals(1, runtime.players.size, "no second native player after dispose")
    }
}
