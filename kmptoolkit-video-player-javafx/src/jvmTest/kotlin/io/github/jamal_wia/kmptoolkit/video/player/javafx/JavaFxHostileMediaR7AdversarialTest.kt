package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7 (hostile media, real JavaFX): sources the operating system hands over in odd shapes. The contract
 * these assert is the one the docs give: `prepare` never throws on failure and never hangs; it settles on
 * Ready or on Error within the ready timeout, a source that cannot be a media file at all (zero length, random
 * bytes) is an Error, a source that is fine however it is reached (a symlink, a unicode folder, a long name)
 * is Ready and plays to its end, and release always returns. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxHostileMediaR7AdversarialTest {

    private lateinit var dir: Path
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        dir = Files.createTempDirectory("kmptoolkit-r7-hostile")
    }

    @AfterTest
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun clipBytes(): ByteArray =
        checkNotNull(TestClip::class.java.classLoader.getResourceAsStream(TestClip.ASSET)).use { it.readBytes() }

    private fun newPlayer(): VideoPlayer =
        createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = READY_TIMEOUT_MS)).also { players += it }

    /** Prepares [file] on a fresh player within a hard bound; returns the settled state. */
    private fun settle(player: VideoPlayer, file: File): VideoPlayerState {
        val began: Long = System.nanoTime()
        runBlocking { withTimeout(READY_TIMEOUT_MS + 10_000L) { player.prepare(VideoSource.File(file.path)) } }
        val tookMs: Long = (System.nanoTime() - began) / 1_000_000L
        val state: VideoPlayerState = player.stateFlow.value
        assertTrue(
            state is VideoPlayerState.Ready || state is VideoPlayerState.Error,
            "${file.name}: prepare returned in $tookMs ms but the state is $state, neither Ready nor Error",
        )
        // A source that is refused must be refused within the ready timeout (plus the load's own overhead).
        assertTrue(tookMs < READY_TIMEOUT_MS + 6_000L, "${file.name}: prepare took $tookMs ms for a ready timeout of $READY_TIMEOUT_MS ms")
        return state
    }

    private fun assertDeadStateIsStable(player: VideoPlayer, label: String) {
        player.play()
        player.seekTo(500L)
        player.seekForward(100L)
        player.setPlaybackSpeed(2f)
        player.pause()
        Thread.sleep(150L)
        assertTrue(player.stateFlow.value is VideoPlayerState.Error, "$label: transport calls on an Error player changed the state to ${player.stateFlow.value}")
    }

    private fun assertPlaysToEnd(player: VideoPlayer, label: String) {
        player.play()
        awaitTrue(timeoutMs = 9_000L, message = { "$label: never completed, state ${player.stateFlow.value}" }) {
            player.stateFlow.value is VideoPlayerState.Completed
        }
    }

    private fun releaseBounded(player: VideoPlayer, label: String) {
        val releaser = Thread { player.release() }.also { it.isDaemon = true; it.start() }
        releaser.join(8_000L)
        assertTrue(!releaser.isAlive, "$label: release() did not return in 8 s")
    }

    @Test
    fun `a file that cannot be media is an Error within the timeout and stays one`() {
        val threadsBefore: Int = Thread.activeCount()
        val cases: Map<String, ByteArray> = mapOf(
            "zero-length.mp4" to ByteArray(0),
            "random-4k.mp4" to Random(7).nextBytes(4096),
            "ten-zero-bytes.mp4" to ByteArray(10),
            "text.mp4" to "this is not a video, it is a sentence".toByteArray(),
            "random-1m.wav" to Random(8).nextBytes(1 shl 20),
        )
        val problems: MutableList<String> = mutableListOf()
        for ((name: String, bytes: ByteArray) in cases) {
            val file = File(dir.toFile(), name).apply { writeBytes(bytes) }
            val player: VideoPlayer = newPlayer()
            val state: VideoPlayerState = settle(player, file)
            if (state !is VideoPlayerState.Error) {
                problems += "$name: settled on $state, expected Error"
            } else {
                assertDeadStateIsStable(player, name)
            }
            releaseBounded(player, name)
        }
        assertEquals(emptyList(), problems)
        Thread.sleep(500L)
        assertTrue(Thread.activeCount() <= threadsBefore + 6, "threads grew from $threadsBefore to ${Thread.activeCount()} after ${cases.size} refused files")
    }

    @Test
    fun `a truncated clip and a clip with the wrong or no extension settle without hanging and play if Ready`() {
        val bytes: ByteArray = clipBytes()
        val cases: Map<String, ByteArray> = mapOf(
            "half.mp4" to bytes.copyOf(bytes.size / 2),
            "first-512-bytes.mp4" to bytes.copyOf(512),
            "head-only-32-bytes.mp4" to bytes.copyOf(32),
            "right-bytes-wrong-extension.wav" to bytes,
            "right-bytes-no-extension" to bytes,
            "right-bytes-upper-case.MP4" to bytes,
            "right-bytes-trailing-dot.mp4." to bytes,
        )
        val log: MutableList<String> = mutableListOf()
        for ((name: String, content: ByteArray) in cases) {
            val file = File(dir.toFile(), name)
            file.writeBytes(content)
            if (!file.isFile) continue
            val player: VideoPlayer = newPlayer()
            val state: VideoPlayerState = settle(player, file)
            log += "$name -> ${state::class.simpleName}"
            if (state is VideoPlayerState.Ready) {
                // A clip that JavaFX accepted must be playable: Completed, or a failure it reports, never a freeze.
                player.play()
                awaitTrue(timeoutMs = 10_000L, message = { "$name: Ready, then play() froze in ${player.stateFlow.value}" }) {
                    val s: VideoPlayerState = player.stateFlow.value
                    s is VideoPlayerState.Completed || s is VideoPlayerState.Error
                }
            } else {
                assertDeadStateIsStable(player, name)
            }
            releaseBounded(player, name)
        }
        println("R7 hostile media outcomes: $log")
    }

    @Test
    fun `a symlink to the clip, a symlink chain, and a unicode and long name play to the end`() {
        val real = File(dir.toFile(), "real.mp4").apply { writeBytes(clipBytes()) }
        val links: List<File> = buildList {
            val direct: Path = Files.createSymbolicLink(File(dir.toFile(), "direct.mp4").toPath(), real.toPath())
            add(direct.toFile())
            val chain: Path = Files.createSymbolicLink(File(dir.toFile(), "chain.mp4").toPath(), direct)
            add(chain.toFile())
            val folder = File(dir.toFile(), "каталог 日本 ünï").apply { mkdirs() }
            add(File(folder, "клип 動画 #1 100%.mp4").apply { writeBytes(clipBytes()) })
            // Close to the 255-byte limit of one name component on APFS and ext4.
            add(File(dir.toFile(), "n".repeat(240) + ".mp4").apply { writeBytes(clipBytes()) })
            val linkedFolder: Path = Files.createSymbolicLink(File(dir.toFile(), "linked folder").toPath(), folder.toPath())
            add(File(linkedFolder.toFile(), "клип 動画 #1 100%.mp4"))
        }
        val problems: MutableList<String> = mutableListOf()
        for (file: File in links) {
            val player: VideoPlayer = newPlayer()
            val state: VideoPlayerState = settle(player, file)
            if (state !is VideoPlayerState.Ready) {
                problems += "${file.name}: $state"
            } else {
                runCatching { assertPlaysToEnd(player, file.name) }.onFailure { problems += "${file.name}: ${it.message}" }
            }
            releaseBounded(player, file.name)
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a dangling symlink, a symlink loop and a path too long for the system are Errors at once`() {
        val dangling: Path = Files.createSymbolicLink(File(dir.toFile(), "dangling.mp4").toPath(), File(dir.toFile(), "gone.mp4").toPath())
        val a = File(dir.toFile(), "a.mp4")
        val b = File(dir.toFile(), "b.mp4")
        Files.createSymbolicLink(a.toPath(), b.toPath())
        Files.createSymbolicLink(b.toPath(), a.toPath())
        val tooLong = File(dir.toFile(), "x".repeat(5_000) + ".mp4")
        for (file: File in listOf(dangling.toFile(), a, tooLong)) {
            val player: VideoPlayer = newPlayer()
            val began: Long = System.nanoTime()
            runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.File(file.path)) } }
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(state is VideoPlayerState.Error, "${file.name.take(20)}: $state")
            assertTrue((System.nanoTime() - began) / 1_000_000L < 3_000L, "a missing file takes no JavaFX round trip")
            assertTrue((state as VideoPlayerState.Error).cause is java.io.FileNotFoundException, "cause ${state.cause}")
            releaseBounded(player, file.name.take(20))
        }
    }

    @Test
    fun `a clip deleted or replaced while it is loaded and then played ends in a sane state`() {
        val problems: MutableList<String> = mutableListOf()
        for (mode: String in listOf("delete", "replace-by-rename", "replace-by-garbage-rename")) {
            val file = File(dir.toFile(), "$mode.mp4").apply { writeBytes(clipBytes()) }
            val player: VideoPlayer = newPlayer()
            val state: VideoPlayerState = settle(player, file)
            if (state !is VideoPlayerState.Ready) {
                problems += "$mode: $state"
                continue
            }
            when (mode) {
                "delete" -> file.delete()
                "replace-by-rename" -> {
                    val other = File(dir.toFile(), "other.tmp").apply { writeBytes(clipBytes()) }
                    Files.move(other.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                else -> {
                    val other = File(dir.toFile(), "garbage.tmp").apply { writeBytes(Random(3).nextBytes(8192)) }
                    Files.move(other.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            player.play()
            player.seekTo(1_000L)
            runCatching {
                awaitTrue(timeoutMs = 12_000L, message = { "$mode: stuck in ${player.stateFlow.value}" }) {
                    val s: VideoPlayerState = player.stateFlow.value
                    s is VideoPlayerState.Completed || s is VideoPlayerState.Error
                }
            }.onFailure { problems += it.message.orEmpty() }
            println("R7 $mode -> ${player.stateFlow.value}")
            releaseBounded(player, mode)
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a sparse two gigabyte file named mp4 is refused within the timeout`() {
        val file = File(dir.toFile(), "huge-sparse.mp4")
        RandomAccessFile(file, "rw").use { it.setLength(2L * 1024 * 1024 * 1024) }
        val player: VideoPlayer = newPlayer()
        val state: VideoPlayerState = settle(player, file)
        assertTrue(state is VideoPlayerState.Error, "two gigabytes of zeros settled on $state")
        releaseBounded(player, "huge")
    }

    private companion object {
        const val READY_TIMEOUT_MS: Long = 4_000L
    }
}
