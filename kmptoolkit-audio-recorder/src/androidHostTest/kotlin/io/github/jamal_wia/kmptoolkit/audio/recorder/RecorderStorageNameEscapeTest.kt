package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows

/**
 * `RecordingStorage.directoryName` is documented as a subdirectory created under the app-private base
 * directory, and `fileNamePrefix` is rejected when it is a nested path. A name that climbs out of the
 * base directory with `..` must not make a recorder write outside it.
 */
@RunWith(AndroidJUnit4::class)
class RecorderStorageNameEscapeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun preparedPathFor(directoryName: String): String? {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
        val config: RecordingStorage? = try {
            RecordingStorage(directoryName = directoryName)
        } catch (rejected: IllegalArgumentException) {
            return null // rejected where it is written: the strict-validation answer
        }
        val recorder: AudioRecorder = createAudioRecorder(context, AudioRecorderConfig(storage = config!!))
        try {
            val result = runBlocking { withTimeout(10_000) { recorder.prepare() } }
            val path: String = (result as? RecorderResult.Success)?.value ?: return null
            File(path).canonicalFile.let { created: File ->
                runBlocking { withTimeout(10_000) { recorder.cancel() } }
                created.delete()
            }
            return path
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `a directory name that climbs out of the base directory is not written outside it`() {
        val base: String = context.filesDir.canonicalPath
        for (name in listOf("..", "../escaped-by-name", "a/../../escaped-by-name2")) {
            val path: String? = preparedPathFor(name)
            if (path != null) {
                val parent: String = File(path).canonicalFile.parentFile!!.path
                assertTrue(
                    parent == base || TestPaths.isInside(base, parent),
                    "directoryName '$name' made the recorder write to $parent, outside $base",
                )
                File(parent).takeIf { it.path != base && it.name.startsWith("escaped-by-name") }?.deleteRecursively()
            }
        }
    }
}
