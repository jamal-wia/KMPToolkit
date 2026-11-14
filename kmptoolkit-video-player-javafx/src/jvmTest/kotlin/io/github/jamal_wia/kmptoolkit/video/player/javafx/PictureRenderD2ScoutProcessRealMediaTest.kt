package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 scout (executor ko), angle (b): the picture under the render settings the app may give the JavaFX toolkit, in a child JVM per
 * setting (a render scale is a JVM flag read when the toolkit starts, so it cannot change inside the Gradle worker JVM). Real media:
 * runs under the exclusive slot. The oracle is `03-guide.md` "Frames and the picture": the frame is "32-bit ARGB at the video's own
 * resolution", whatever the display scale, and the clip is solid red, so every settled pixel must be opaque red.
 *
 * `prism.allowhidpi` is the scale switch of macOS and of the other desktop pipelines (`true` by default); `prism.order=sw` runs the
 * software renderer, a different path through `Node.snapshot`.
 */
class PictureRenderD2ScoutProcessRealMediaTest {

    private fun runChild(vararg flags: String): Map<String, String> {
        assumeJavaFxMedia()
        val java: String = File(System.getProperty("java.home"), "bin/java").path
        val process: Process = ProcessBuilder(
            listOf(java) + childJvmCrashGuardArgs() + flags.toList() + listOf(
                "-cp", System.getProperty("java.class.path"),
                "io.github.jamal_wia.kmptoolkit.video.player.javafx.PictureRenderChildD2ScoutMain",
            ),
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        val finished: Boolean = process.waitFor(90L, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000L)
        assertTrue(finished, "child JVM hung for ${flags.toList()}:\n$output")
        return output.lines().filter { it.startsWith("RESULT ") }.associate {
            val pair: List<String> = it.removePrefix("RESULT ").split("=", limit = 2)
            pair[0] to pair.getOrElse(1) { "" }
        }.also { assertTrue(it.isNotEmpty(), "no RESULT from ${flags.toList()}:\n$output") }
    }

    private fun assertRedAtTheClipsOwnSize(result: Map<String, String>) {
        assertEquals(null, result["crashed"], "$result")
        assertEquals("[(${TestClip.WIDTH}, ${TestClip.HEIGHT})]", result["sizes"], "the frames were not of the video's own resolution: $result")
        assertEquals("true", result["arraysMatchSize"], "$result")
        assertTrue((result["settledFrames"] ?: "0").toInt() >= 5, "too few frames to judge: $result")
        assertEquals("0", result["badPixels"], "a settled pixel of the solid red clip was not opaque red: $result")
    }

    @Test
    fun theDefaultRenderSettingsGiveOpaqueRedFramesAtTheClipsOwnSize() = assertRedAtTheClipsOwnSize(runChild())

    @Test
    fun withHighDpiRenderingSwitchedOffTheFramesAreStillTheClipsOwnSize() = assertRedAtTheClipsOwnSize(runChild("-Dprism.allowhidpi=false"))

    @Test
    fun withHighDpiRenderingSwitchedOnTheFramesAreStillTheClipsOwnSize() = assertRedAtTheClipsOwnSize(runChild("-Dprism.allowhidpi=true"))

    @Test
    fun theSoftwareRendererGivesOpaqueRedFramesAtTheClipsOwnSize() = assertRedAtTheClipsOwnSize(runChild("-Dprism.order=sw"))
}
