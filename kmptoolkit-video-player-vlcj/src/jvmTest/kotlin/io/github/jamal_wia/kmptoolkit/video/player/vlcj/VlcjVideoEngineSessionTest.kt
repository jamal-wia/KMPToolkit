package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * [VlcjVideoEngine]'s session logic against [FakeVlcRuntime] — everything the engine decides on its
 * own, which runs on every machine whether or not VLC is installed. `VlcjVideoEngineTest` covers
 * the same engine against real libvlc.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjVideoEngineSessionTest {

    private val runtime = FakeVlcRuntime()
    private val runtimeCreations = AtomicInteger()
    private val discoveryThreads: MutableList<String> = CopyOnWriteArrayList()
    private val teardownThread: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, TEARDOWN_THREAD).apply { isDaemon = true } }
    private val listener = RecordingListener()
    private val tempFiles: MutableList<File> = CopyOnWriteArrayList()

    private fun newEngine(
        discover: () -> Boolean = { true },
        resolve: (VideoSource, ClassLoader) -> ResolvedMedia = VlcMediaResolver::resolve,
    ): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = {
            discoveryThreads += Thread.currentThread().name
            discover()
        },
        createRuntime = {
            runtimeCreations.incrementAndGet()
            runtime.record("createRuntime")
            runtime
        },
        teardown = teardownThread,
        resolve = resolve,
    ).also { it.setListener(listener) }

    private val engine: VlcjVideoEngine by lazy { newEngine() }

    @AfterTest
    fun tearDown() {
        engine.dispose()
        drainTeardown()
        runtime.shutdown()
        teardownThread.shutdownNow()
        tempFiles.forEach { it.delete() }
    }

    private fun drainTeardown() {
        teardownThread.submit {}.get(FakeVlcRuntime.WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private suspend fun loadClip(target: VlcjVideoEngine = engine) {
        target.load(VideoSource.File(TestClip.file.path))
    }

    /** Loads, starts, and lets VLC report that output began. */
    private suspend fun loadAndStart(): FakeVlcPlayer {
        loadClip()
        engine.start()
        runtime.player.fire { playing() }
        runtime.drainTasks()
        return runtime.player
    }

    private fun temporaryMedia(): ResolvedMedia {
        val file: File = Files.createTempFile("kmptoolkit-vlcj-test-", ".mp4").toFile()
        tempFiles += file
        return ResolvedMedia(file.absolutePath, emptyList(), isLocal = true, temporaryFile = file)
    }

    // --- loading -----------------------------------------------------------------------------

    @Test
    fun `a load reports the parsed duration and display size and plays nothing`() = runBlocking<Unit> {
        runtime.parsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 2, videoSize = VideoSize(64, 48))

        loadClip()

        assertEquals(2_000L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertEquals(2_000L, engine.bufferedPositionMs(), "a local source is fully buffered")
        assertEquals(listOf<VideoSize?>(VideoSize(64, 48)), listener.sizes)
        assertEquals(listOf("prepare", "parse"), runtime.player.calls)
        assertEquals(TestClip.file.absolutePath, runtime.player.mrl)
        assertNull(engine.frames.value)
    }

    @Test
    fun `nothing slow runs on the calling thread`() = runBlocking<Unit> {
        val caller: String = Thread.currentThread().name
        loadClip()
        val first: FakeVlcPlayer = runtime.player

        loadClip() // closes the first session
        engine.release() // closes the second
        drainTeardown()

        assertTrue(discoveryThreads.isNotEmpty() && discoveryThreads.none { it == caller }, "discovery: $discoveryThreads")
        val slowCalls: List<String> = runtime.log.filter {
            it.startsWith("createRuntime") || it.startsWith("newPlayer") ||
                it.startsWith("stop") || it.startsWith("release")
        }
        assertTrue(slowCalls.isNotEmpty())
        assertTrue(slowCalls.none { it.endsWith("@$caller") }, "on the caller's thread: $slowCalls")
        assertEquals(TEARDOWN_THREAD, first.threadOf("release"))
        assertEquals(TEARDOWN_THREAD, runtime.player.threadOf("release"))
    }

    @Test
    fun `a local source without any track fails the load and leaves nothing loaded`() = runBlocking<Unit> {
        runtime.parsedInfo = VlcParsedInfo(durationMs = 0L, trackCount = 0, videoSize = null)

        assertFailsWith<VlcPlaybackException> { loadClip() }
        drainTeardown()

        assertEquals(0L, engine.durationMs())
        assertTrue("release" in runtime.player.calls, "the failed session's player is freed")
    }

    @Test
    fun `a parse that fails or times out fails the load with VlcPlaybackException`() = runBlocking<Unit> {
        runtime.parseResult = VlcParseStatus.FAILED
        assertFailsWith<VlcPlaybackException> { loadClip() }

        runtime.parseResult = VlcParseStatus.TIMEOUT
        assertFailsWith<VlcPlaybackException> { loadClip() }

        runtime.parseResult = VlcParseStatus.DONE
        loadClip()
        assertEquals(2_000L, engine.durationMs())
    }

    @Test
    fun `cancelling a load waiting for the parser frees its player`() = runBlocking<Unit> {
        runtime.parseResult = null
        val load = async(Dispatchers.Default) { loadClip() }
        awaitCondition { runtime.players.isNotEmpty() && "parse" in runtime.player.calls }

        load.cancel()
        load.join()
        drainTeardown()

        assertTrue(load.isCancelled)
        assertEquals(listOf("prepare", "parse", "stopParsing", "stop", "release"), runtime.player.calls)
        assertEquals(0L, engine.durationMs())
    }

    // --- libvlc instance lifetime ------------------------------------------------------------

    @Test
    fun `one libvlc instance serves every load, release and failed load until dispose`() = runBlocking<Unit> {
        loadClip()
        engine.release()
        loadClip()
        runtime.parseResult = VlcParseStatus.FAILED
        assertFailsWith<VlcPlaybackException> { loadClip() }
        runtime.parseResult = VlcParseStatus.DONE
        loadClip()
        engine.release()
        drainTeardown()

        assertEquals(1, runtimeCreations.get())
        assertEquals(0, runtime.released, "release must keep the libvlc instance")

        engine.dispose()
        engine.dispose()
        drainTeardown()

        assertEquals(1, runtime.released)
        val lastPlayerRelease: Int = runtime.log.indexOfLast { it.startsWith("release@") }
        val runtimeRelease: Int = runtime.log.indexOfFirst { it.startsWith("runtime.release@") }
        assertTrue(lastPlayerRelease in 0 until runtimeRelease, "libvlc is freed after its players: ${runtime.log}")
        assertEquals("runtime.release@$TEARDOWN_THREAD", runtime.log[runtimeRelease])
    }

    @Test
    fun `releasing the player frees libvlc while unloading it keeps the instance`() = runBlocking<Unit> {
        val player: VideoPlayer = createVideoPlayer(engine)
        player.prepare(VideoSource.File(TestClip.file.path))
        assertTrue(player.stateFlow.value.isPlayable, "the fake runtime prepares the clip: ${player.stateFlow.value}")

        player.unload()
        drainTeardown()
        assertEquals(0, runtime.released, "unload must keep the libvlc instance")

        player.release()
        player.release()
        drainTeardown()

        assertEquals(1, runtimeCreations.get())
        assertEquals(1, runtime.released, "release must free the libvlc instance exactly once")
    }

    @Test
    fun `a libvlc instance that cannot be created is retried by the next load`() = runBlocking<Unit> {
        var failNext = true
        val flaky = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = {
                runtimeCreations.incrementAndGet()
                if (failNext) {
                    failNext = false
                    throw VlcUnavailableException("refused")
                }
                runtime
            },
            teardown = teardownThread,
        )
        try {
            assertFailsWith<VlcUnavailableException> { loadClip(flaky) }
            loadClip(flaky)

            assertEquals(2, runtimeCreations.get())
            assertEquals(2_000L, flaky.durationMs())
        } finally {
            flaky.dispose()
        }
    }

    // --- transport guards --------------------------------------------------------------------

    @Test
    fun `before the first start pause is ignored and a seek is remembered, not sent`() = runBlocking<Unit> {
        loadClip()

        engine.pause()
        engine.seekTo(1_200L)

        assertEquals(1_200L, engine.positionMs(), "the playhead reports the pending seek")
        assertEquals(listOf("prepare", "parse"), runtime.player.calls)
    }

    @Test
    fun `a pending seek and the settings are applied once output starts, off the event thread`() = runBlocking<Unit> {
        loadClip()
        engine.setVolume(0.25f)
        engine.setSpeed(1.5f)
        engine.seekTo(800L)
        assertTrue(runtime.player.calls.none { it.startsWith("set") }, "nothing reaches libvlc before start")

        engine.start()
        assertEquals(800L, engine.positionMs(), "still pending until output exists")
        runtime.player.fire { playing() }
        runtime.drainTasks()

        val player: FakeVlcPlayer = runtime.player
        assertTrue("setVolume(25)" in player.calls, "${player.calls}")
        assertTrue("setRate(1.5)" in player.calls, "${player.calls}")
        assertTrue("setTime(800)" in player.calls, "${player.calls}")
        assertEquals(FakeVlcRuntime.TASK_THREAD, player.threadOf("setTime(800)"))
        assertEquals(FakeVlcRuntime.TASK_THREAD, player.threadOf("setVolume(25)"))

        player.playhead = 950L
        assertEquals(950L, engine.positionMs(), "once applied, the playhead is libvlc's")
    }

    @Test
    fun `settings changed during playback reach libvlc at once`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        player.calls.clear()
        player.callThreads.clear()

        engine.setVolume(0f)
        engine.setSpeed(2f)

        assertEquals(listOf("setVolume(0)", "setRate(1.0)", "setVolume(0)", "setRate(2.0)"), player.calls)
    }

    @Test
    fun `a seek during playback moves libvlc's playhead`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()

        engine.seekTo(1_500L)
        player.playhead = 1_500L

        assertTrue("setTime(1500)" in player.calls)
        assertEquals(1_500L, engine.positionMs())
    }

    @Test
    fun `looping set before a load is handed to the new player`() = runBlocking<Unit> {
        engine.setLooping(true)
        loadClip()

        assertTrue(runtime.player.repeat())

        engine.setLooping(false)
        assertFalse(runtime.player.repeat())
    }

    // --- events ------------------------------------------------------------------------------

    @Test
    fun `buffering is reported only once the source is loaded, and only on change`() = runBlocking<Unit> {
        runtime.parseResult = null
        val load = async(Dispatchers.Default) { loadClip() }
        awaitCondition { runtime.players.isNotEmpty() && "parse" in runtime.player.calls }
        val player: FakeVlcPlayer = runtime.player

        player.fire { buffering(10f) }
        assertTrue(listener.events.none { it.startsWith("buffering") }, "nothing is loaded yet")

        player.fire { parsed(VlcParseStatus.DONE) }
        load.await()
        player.fire { buffering(10f) }
        player.fire { buffering(60f) }
        player.fire { buffering(100f) }
        player.fire { buffering(40f) }
        player.fire { paused() }

        assertEquals(
            listOf("buffering=true", "buffering=false", "buffering=true", "buffering=false"),
            listener.events.filter { it.startsWith("buffering") },
        )
    }

    @Test
    fun `the end is reported once and parks the playhead at the duration`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()

        player.fire { finished() }

        assertEquals(1, listener.completions)
        assertEquals(2_000L, engine.positionMs())
    }

    @Test
    fun `the end is not an end while looping`() = runBlocking<Unit> {
        engine.setLooping(true)
        val player: FakeVlcPlayer = loadAndStart()

        player.fire { finished() }

        assertEquals(0, listener.completions)
    }

    @Test
    fun `a seek after the end stays paused until start, then lands`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        player.fire { finished() }
        player.calls.clear()

        engine.seekTo(500L)
        engine.pause()

        assertEquals(500L, engine.positionMs())
        assertEquals(emptyList(), player.calls, "no native call — in particular no play — after the end")

        engine.start()
        player.fire { playing() }
        runtime.drainTasks()
        assertEquals("play", player.calls.first())
        assertTrue("setTime(500)" in player.calls)
    }

    @Test
    fun `a playback error is reported as VlcPlaybackException`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()

        player.fire { error() }

        assertTrue(listener.failures.single() is VlcPlaybackException)
    }

    @Test
    fun `a listener that restarts from onCompleted is served on VLC's task thread`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        listener.reaction = { event ->
            if (event == "completed") {
                engine.seekTo(0L)
                engine.start()
            }
        }
        player.calls.clear()
        player.callThreads.clear()

        player.fire { finished() }
        runtime.drainTasks()

        assertEquals(listOf("play"), player.calls)
        assertEquals(FakeVlcRuntime.TASK_THREAD, player.threadOf("play"), "never into libvlc from the event thread")
        assertEquals(0L, engine.positionMs())
    }

    @Test
    fun `transport called from a VLC callback is re-submitted, not run on the event thread`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        listener.reaction = { event -> if (event == "buffering=true") engine.pause() }

        player.fire { buffering(20f) }
        runtime.drainTasks()

        assertEquals(FakeVlcRuntime.TASK_THREAD, player.threadOf("pause"))
    }

    // --- video size and frames ---------------------------------------------------------------

    @Test
    fun `the decoder's stored size never replaces the parsed display size`() = runBlocking<Unit> {
        // A 720x480 anamorphic source shown at 16:9.
        runtime.parsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 1, videoSize = VideoSize(853, 480))
        val player: FakeVlcPlayer = loadAndStart()

        player.fire { bufferFormat(720, 480) }

        assertEquals(listOf<VideoSize?>(VideoSize(853, 480)), listener.sizes)
    }

    @Test
    fun `without a parsed size the decoded size is reported`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()

        player.fire { bufferFormat(0, 0) }
        player.fire { bufferFormat(64, 48) }
        player.fire { bufferFormat(64, 48) }

        assertEquals(listOf<VideoSize?>(VideoSize(64, 48)), listener.sizes)
    }

    @Test
    fun `every frame has its own pixels, untouched by the frames after it`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()

        player.displayFrame(2, 2, 0x00FF0000)
        val first: VideoFrame = assertNotNull(engine.frames.value)
        player.displayFrame(2, 2, 0x0000FF00)
        player.displayFrame(2, 2, 0x000000FF)
        player.displayFrame(2, 2, 0x00FFFFFF)
        val last: VideoFrame = assertNotNull(engine.frames.value)

        assertNotSame(first.pixels, last.pixels)
        assertContentEquals(IntArray(4) { 0xFFFF0000.toInt() }, first.pixels)
        assertContentEquals(IntArray(4) { 0xFFFFFFFF.toInt() }, last.pixels)
    }

    // --- release -----------------------------------------------------------------------------

    @Test
    fun `after release no callback, frame or native call gets through`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        player.displayFrame(2, 2, 0x00123456)
        val eventsBefore: Int = listener.events.size

        engine.release()
        drainTeardown()
        val callsAfterRelease: Int = player.calls.size
        player.fire { finished() }
        player.fire { error() }
        player.fire { buffering(10f) }
        player.fire { bufferFormat(10, 10) }
        player.displayFrame(2, 2, 0x00654321)
        engine.start()
        engine.pause()
        engine.seekTo(100L)
        engine.setVolume(0.5f)

        assertEquals(eventsBefore, listener.events.size)
        assertNull(engine.frames.value)
        assertEquals(callsAfterRelease, player.calls.size)
        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
    }

    @Test
    fun `release returns while libvlc is still stopping a stalled input`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        val gate = CountDownLatch(1)
        player.stopGate = gate

        val started: Long = System.nanoTime()
        engine.release()
        val tookMs: Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        engine.release()

        assertTrue(tookMs < 1_000L, "release blocked for $tookMs ms")
        awaitCondition { "stop" in player.calls }
        assertFalse("release" in player.calls, "the player is released only after stop returned")
        gate.countDown()
        drainTeardown()
        assertEquals(listOf("stopParsing", "stop", "release"), player.calls.takeLast(3))
        assertEquals(TEARDOWN_THREAD, player.threadOf("stop"))
    }

    @Test
    fun `release from inside a VLC callback neither deadlocks nor tears down on a VLC thread`() = runBlocking<Unit> {
        val player: FakeVlcPlayer = loadAndStart()
        listener.reaction = { event -> if (event == "completed") engine.release() }

        player.fire { finished() }
        drainTeardown()

        assertEquals(TEARDOWN_THREAD, player.threadOf("release"))
        assertEquals(0L, engine.durationMs())
    }

    @Test
    fun `a new load closes the old session before the new one reports`() = runBlocking<Unit> {
        val first: FakeVlcPlayer = loadAndStart()

        loadClip()
        drainTeardown()
        first.fire { finished() }
        first.fire { bufferFormat(32, 32) }

        assertEquals(0, listener.completions, "the replaced source's end is not reported")
        assertTrue(listener.sizes.none { it == VideoSize(32, 32) })
        assertEquals(listOf("stopParsing", "stop", "release"), first.calls.takeLast(3))
        assertEquals(2, runtime.players.size)
    }

    @Test
    fun `a temporary asset copy is deleted after its player is released`() = runBlocking<Unit> {
        val media: ResolvedMedia = temporaryMedia()
        val copy: File = assertNotNull(media.temporaryFile)
        val existedAtPlayerRelease = CopyOnWriteArrayList<Boolean>()
        val withCopy: VlcjVideoEngine = newEngine(resolve = { _, _ -> media })
        try {
            withCopy.load(VideoSource.Asset("video/packed.mp4"))
            runtime.player.onRelease = { existedAtPlayerRelease += copy.exists() }

            withCopy.release()
            drainTeardown()

            assertEquals(listOf(true), existedAtPlayerRelease)
            assertFalse(copy.exists())
        } finally {
            withCopy.dispose()
        }
    }

    @Test
    fun `a cancellation landing right after the source resolved deletes the temporary copy`() = runBlocking<Unit> {
        val media: ResolvedMedia = temporaryMedia()
        lateinit var load: Job
        val cancelling: VlcjVideoEngine = newEngine(
            resolve = { _, _ ->
                load.cancel() // lands while resolve is finishing; withContext then throws on return
                media
            },
        )
        try {
            load = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                cancelling.load(VideoSource.Asset("video/packed.mp4"))
            }
            load.start()
            load.join()

            assertTrue(load.isCancelled)
            assertFalse(assertNotNull(media.temporaryFile).exists())
            assertTrue(discoveryThreads.isEmpty(), "the load stopped before looking for VLC")
        } finally {
            cancelling.dispose()
        }
    }

    @Test
    fun `a cancellation landing during discovery deletes the temporary copy`() = runBlocking<Unit> {
        val media: ResolvedMedia = temporaryMedia()
        lateinit var load: Job
        val cancelling: VlcjVideoEngine = newEngine(
            discover = {
                load.cancel()
                true
            },
            resolve = { _, _ -> media },
        )
        try {
            load = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                cancelling.load(VideoSource.Asset("video/packed.mp4"))
            }
            load.start()
            load.join()
            drainTeardown()

            assertTrue(load.isCancelled)
            assertFalse(assertNotNull(media.temporaryFile).exists())
            assertTrue(runtime.players.isEmpty())
        } finally {
            cancelling.dispose()
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(FakeVlcRuntime.WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TEARDOWN_THREAD = "test-vlcj-teardown"
        const val POLL_MS = 5L
    }
}
