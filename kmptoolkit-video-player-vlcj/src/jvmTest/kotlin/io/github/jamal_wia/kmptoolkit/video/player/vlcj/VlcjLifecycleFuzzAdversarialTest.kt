package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Seeded lifecycle chaos for the VLCJ engine against [FakeVlcRuntime]: a load racing release or
 * dispose at random offsets, and the whole player under concurrent prepare, unload, transport and
 * release calls. Invariants checked at the end: every native player released exactly once and last,
 * the libvlc instance freed once per engine, no temporary asset copy left. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLifecycleFuzzAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private val runtimeCreations = AtomicInteger()
    private val tempFiles: MutableList<File> = CopyOnWriteArrayList()
    private val loaders: MutableList<URLClassLoader> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
        loaders.forEach { it.close() }
        tempFiles.forEach { it.delete() }
    }

    private fun newEngine(classLoader: ClassLoader? = null): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = { true },
        classLoader = classLoader,
        createRuntime = { runtimeCreations.incrementAndGet(); runtime },
    )

    private fun awaitTrue(timeoutMs: Long = 15_000L, message: () -> String, condition: () -> Boolean) {
        val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms: ${message()}")
            Thread.sleep(20L)
        }
    }

    private fun jarLoader(extension: String): URLClassLoader {
        val jar: File = Files.createTempFile("d2-fuzz-", ".jar").toFile().also { tempFiles += it }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("assets/clip.$extension"))
            out.write(ByteArray(1_024) { it.toByte() })
            out.closeEntry()
        }
        return URLClassLoader(arrayOf(jar.toURI().toURL()), null).also { loaders += it }
    }

    private fun copiesOnDisk(extension: String): List<File> =
        File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("kmptoolkit-video-") && f.name.endsWith(".$extension") }
            .orEmpty().toList()

    private fun spin(iterations: Int) {
        var sink = 0
        repeat(iterations) { sink += it }
        if (sink == -1) println(sink)
    }

    private fun assertEveryPlayerFreedOnce(label: String) {
        awaitTrue(message = { "$label: players=${runtime.players.size} calls=${runtime.players.map { p -> p.calls.count { it == "release" } }}" }) {
            runtime.players.all { p -> p.calls.count { it == "release" } == 1 }
        }
        runtime.players.forEachIndexed { index, p ->
            assertEquals("release", p.calls.last(), "$label: player $index was called after its release: ${p.calls}")
            assertEquals(1, p.calls.count { it == "stop" }, "$label: player $index stopped once")
        }
    }

    @Test
    fun `a load racing release or dispose at random offsets leaves no native player or asset copy`() = runBlocking<Unit> {
        val ext = "d2fuzzrace"
        val loader: URLClassLoader = jarLoader(ext)
        val asset = VideoSource.Asset("assets/clip.$ext")
        val random = Random(2024)
        val liveBefore: Int = AssetCopies.liveCount
        repeat(40) { wave: Int ->
            val engines: List<VlcjVideoEngine> = List(8) { newEngine(loader) }
            withTimeout(30_000L) {
                engines.mapIndexed { index: Int, engine: VlcjVideoEngine ->
                    val offset: Int = random.nextInt(0, 400_000)
                    val viaDispose: Boolean = (wave + index) % 2 == 0
                    val load = async(Dispatchers.Default) { runCatching { engine.load(asset) } }
                    val racer = async(Dispatchers.Default) {
                        spin(offset)
                        if (viaDispose) engine.dispose() else engine.release()
                    }
                    racer.await()
                    load.await()
                }
            }
            // The SPI never races a call with the load it cancels, so the final state is the engine's own: dispose.
            engines.forEach { it.dispose() }
        }
        assertEveryPlayerFreedOnce("race")
        awaitTrue(message = { "runtimes created=${runtimeCreations.get()} released=${runtime.released}" }) {
            runtime.released == runtimeCreations.get()
        }
        awaitTrue(message = { "copies=${copiesOnDisk(ext)} live=${AssetCopies.liveCount}" }) {
            copiesOnDisk(ext).isEmpty() && AssetCopies.liveCount == liveBefore
        }
    }

    @Test
    fun `the player under concurrent prepare unload transport and cancellation ends with every native player freed once`() = runBlocking<Unit> {
        val ext = "d2fuzzplayer"
        val loader: URLClassLoader = jarLoader(ext)
        val liveBefore: Int = AssetCopies.liveCount
        val engine: VlcjVideoEngine = newEngine(loader)
        val player = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 5L), Dispatchers.Default)
        val sources: List<VideoSource> = listOf(
            VideoSource.File(TestClip.file.path),
            VideoSource.Asset("assets/clip.$ext"),
            VideoSource.Remote("http://127.0.0.1:1/never.mp4"),
        )
        withTimeout(60_000L) {
            List(6) { seed: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 31 + 5)
                    repeat(50) {
                        when (random.nextInt(9)) {
                            0, 1 -> withTimeoutOrNull(random.nextLong(1L, 30L)) { player.prepare(sources[random.nextInt(sources.size)]) }
                            2 -> player.unload()
                            3 -> player.play()
                            4 -> player.pause()
                            5 -> player.seekTo(random.nextLong(0L, 2_000L))
                            6 -> player.setVolume(random.nextFloat())
                            7 -> player.stop()
                            else -> player.replay()
                        }
                        if (random.nextInt(4) == 0) kotlinx.coroutines.yield()
                    }
                }
            }.awaitAll()
            player.release()
        }
        assertEveryPlayerFreedOnce("player fuzz")
        awaitTrue(message = { "runtimes created=${runtimeCreations.get()} released=${runtime.released}" }) { runtime.released == runtimeCreations.get() }
        assertEquals(1, runtime.released, "one libvlc instance for one engine, freed once")
        awaitTrue(message = { "copies=${copiesOnDisk(ext)} live=${AssetCopies.liveCount}" }) {
            copiesOnDisk(ext).isEmpty() && AssetCopies.liveCount == liveBefore
        }
        assertTrue(runtime.players.isNotEmpty(), "the fuzz never loaded anything")
    }
}
