package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Round 12, Q4: what a 4.0.0 recorder left on disk and exposed, read by this version from a 4.0.0-shaped fixture.
 * 4.0.0 wrote `<app files dir>/<application id>/recording_<epochMillis>_<32 hex>.m4a` and never listed, renamed or
 * migrated anything; the CHANGELOG states "No file name or directory layout changes" for this release, and the
 * public API dump of the module has no difference to the 4.0.0 tag. So: a default-configured recorder resolves to
 * the same directory with a name of the same shape, leaves every file of the 4.0.0 directory alone (prepare,
 * cancel, stop, release), and an absolute path is judged as the 4.0.0 rule judged it (`non-blank and starts with
 * '/'`) on a POSIX host (the CHANGELOG states the one difference, a Windows host, where this check does not apply).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [StrictShadowMediaRecorder::class])
class Recorder400FixtureR12Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var legacyDirectory: File
    private val legacyFiles: MutableList<File> = mutableListOf()

    @Before
    fun setUp() {
        StrictRecorderWorld.reset()
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        legacyDirectory = File(context.filesDir, context.packageName).also { it.mkdirs() }
        val random = kotlin.random.Random(400L)
        repeat(5) { index: Int ->
            val token: String = (1..32).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }
            legacyFiles += File(legacyDirectory, "recording_${1_700_000_000_000L + index}_$token.m4a").also { it.writeText("legacy $index") }
        }
    }

    @After
    fun tearDown() {
        legacyDirectory.deleteRecursively()
    }

    @Test
    fun `a default recorder writes next to the 4 0 0 files with a name of the same shape and never touches them`() {
        val recorder: AudioRecorder = createAudioRecorder(context, AudioRecorderConfig(minimumFreeSpaceBytes = 0L))
        runBlocking {
            withTimeout(60_000) {
                val first: String = recorder.prepare().getOrNull() ?: error("prepare failed: ${recorder.state.value}")
                assertEquals(legacyDirectory.canonicalPath, File(first).parentFile!!.canonicalPath)
                assertTrue(
                    Regex("recording_\\d{13}_[0-9a-f]{32}\\.m4a").matches(File(first).name),
                    "the generated name left the 4.0.0 shape: ${File(first).name}",
                )
                assertTrue(recorder.cancel().isSuccess)
                val second: String = recorder.prepare().getOrNull() ?: error("second prepare failed")
                assertTrue(recorder.start().isSuccess)
                val finished: RecordedFile = recorder.stop().getOrNull() ?: error("stop failed")
                assertEquals(second, finished.path)
                assertTrue(File(finished.path).isFile)
                recorder.prepare()
                recorder.release()
                recorder.release()
            }
        }
        for ((index: Int, file: File) in legacyFiles.withIndex()) {
            assertEquals("legacy $index", file.takeIf { it.isFile }?.readText(), "a 4.0.0 file was deleted or changed: ${file.name}")
        }
        assertEquals(legacyFiles.size + 1, legacyDirectory.listFiles()!!.size, "only the new recording may be added to the 4.0.0 directory")
    }

    @Test
    fun `an explicit path onto a 4 0 0 file replaces it as documented and nothing else of that directory`() {
        val target: File = legacyFiles[2]
        val untouched: List<File> = legacyFiles - target
        val recorder: AudioRecorder = createAudioRecorder(context, AudioRecorderConfig(minimumFreeSpaceBytes = 0L))
        runBlocking {
            withTimeout(60_000) {
                assertEquals(RecorderResult.Success(target.path), recorder.prepare(target.path))
                assertTrue(recorder.start().isSuccess)
                assertEquals(target.path, recorder.stop().getOrNull()?.path)
            }
        }
        recorder.release()
        assertTrue(target.isFile)
        for (file: File in untouched) assertTrue(file.isFile && file.readText().startsWith("legacy"), "${file.name} was touched")
    }

    @Test
    fun `the absolute path rule judges strings as the 4 0 0 rule did on a POSIX host`() {
        // path-hygiene-ignore: PH1 the test only decides whether the host is POSIX, to skip a comparison that holds on POSIX only
        if (File.separatorChar != '/') return // a Windows host: the one stated difference, NOT VERIFIED (Windows)
        val strings: List<String> = listOf(
            "/", "//", "//x.m4a", "/a/../b.m4a", "/a//b.m4a", "/a/./b.m4a", "/a/b/", "C:\\x.m4a", "C:/x.m4a", "\\\\server\\share\\x.m4a",
            "~/x.m4a", "file:///x.m4a", "x.m4a", "./x.m4a", "../x.m4a", "", " ", "  /x.m4a", "/ x.m4a", "\u0000", "/a\u0000b",
            "\u202e/x.m4a", "\uFF0Fx.m4a",
        )
        val fileSystem = AndroidRecordingFileSystem(context)
        for (candidate: String in strings) {
            val oldRule: Boolean = candidate.isNotBlank() && candidate.startsWith("/")
            val newRule: Boolean = candidate.isNotBlank() && fileSystem.isAbsolute(candidate)
            assertEquals(oldRule, newRule, "'${candidate.replace("\u0000", "<NUL>")}' is judged differently from 4.0.0")
        }
        // The same judgement through the public API: relative strings are refused before the file system is touched.
        val recorder: AudioRecorder = createAudioRecorder(context, AudioRecorderConfig(minimumFreeSpaceBytes = 0L))
        try {
            runBlocking {
                withTimeout(60_000) {
                    for (candidate: String in listOf("C:\\x.m4a", "~/x.m4a", "file:///x.m4a", "x.m4a", "", " ")) {
                        assertEquals(RecorderError.DirectoryNotWritable(candidate), recorder.prepare(candidate).errorOrNull(), candidate)
                    }
                }
            }
        } finally {
            recorder.release()
        }
        assertEquals(Files.list(legacyDirectory.toPath()).use { it.count() }.toInt(), legacyFiles.size)
    }
}
