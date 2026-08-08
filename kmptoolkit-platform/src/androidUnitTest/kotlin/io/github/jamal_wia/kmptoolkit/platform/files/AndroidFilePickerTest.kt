package io.github.jamal_wia.kmptoolkit.platform.files

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * A host stub stands in for the activity-result plumbing, which is the consumer's half of the
 * seam. Everything asserted here is this module's half: the filter that reaches the chooser, and
 * what happens to the URI that comes back.
 */
private class StubHost(
    private val canLaunch: Boolean = true,
    private val resultUri: Uri? = null,
    private val throwOnLaunch: Boolean = false,
) : FilePickerHost {

    var launchedWith: Array<String>? = null
        private set

    override fun launch(mimeTypes: Array<String>, onResult: (Uri?) -> Unit): Boolean {
        launchedWith = mimeTypes
        if (throwOnLaunch) error("the activity is gone")
        if (!canLaunch) return false
        onResult(resultUri)
        return true
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidFilePickerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File = File(context.filesDir, "picker-test").apply { mkdirs() }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun fileUri(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(directory, name).apply { writeBytes(bytes) })

    @Test
    fun `a host that cannot launch reports the picker unavailable`() = runTest {
        val picker: FilePicker = createFilePicker(context, StubHost(canLaunch = false))

        assertEquals(PickResult.Unavailable, picker.pick())
    }

    @Test
    fun `a host that throws reports the picker unavailable rather than propagating`() = runTest {
        val picker: FilePicker = createFilePicker(context, StubHost(throwOnLaunch = true))

        assertEquals(PickResult.Unavailable, picker.pick())
    }

    @Test
    fun `a dismissed chooser is a cancellation`() = runTest {
        val picker: FilePicker = createFilePicker(context, StubHost(resultUri = null))

        assertEquals(PickResult.Cancelled, picker.pick())
    }

    @Test
    fun `a picked file comes back with its bytes`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val picker: FilePicker =
            createFilePicker(context, StubHost(resultUri = fileUri("report.pdf", bytes)))

        val result: PickResult = picker.pick()

        val picked = assertIs<PickResult.Picked>(result)
        assertContentEquals(bytes, picked.file.bytes)
        assertEquals("report.pdf", picked.file.name)
        assertEquals(4, picked.file.sizeBytes)
    }

    @Test
    fun `an empty file is picked successfully rather than treated as a failure`() = runTest {
        val picker: FilePicker =
            createFilePicker(context, StubHost(resultUri = fileUri("empty.txt", ByteArray(0))))

        val picked = assertIs<PickResult.Picked>(picker.pick())

        assertEquals(0, picked.file.sizeBytes)
    }

    @Test
    fun `a file over the cap is refused and reports both sizes`() = runTest {
        val picker: FilePicker = createFilePicker(
            context,
            StubHost(resultUri = fileUri("big.bin", ByteArray(64))),
            FilePickerConfig(maxBytes = 10),
        )

        val result: PickResult = picker.pick()

        assertEquals(PickResult.TooLarge(sizeBytes = 64, maxBytes = 10), result)
    }

    @Test
    fun `a file exactly at the cap is accepted`() = runTest {
        val picker: FilePicker = createFilePicker(
            context,
            StubHost(resultUri = fileUri("edge.bin", ByteArray(10))),
            FilePickerConfig(maxBytes = 10),
        )

        assertIs<PickResult.Picked>(picker.pick())
    }

    @Test
    fun `a uri that cannot be read reports a failure`() = runTest {
        val missing: Uri = Uri.fromFile(File(directory, "gone.pdf"))
        val picker: FilePicker = createFilePicker(context, StubHost(resultUri = missing))

        assertIs<PickResult.Failed>(picker.pick())
    }

    @Test
    fun `the requested mime types reach the chooser`() = runTest {
        val host = StubHost(resultUri = null)
        val picker: FilePicker = createFilePicker(context, host)

        picker.pick(listOf("application/pdf", "image/png"))

        assertContentEquals(arrayOf("application/pdf", "image/png"), host.launchedWith)
    }

    @Test
    fun `an empty filter asks the chooser for anything`() = runTest {
        val host = StubHost(resultUri = null)
        val picker: FilePicker = createFilePicker(context, host)

        picker.pick()

        assertContentEquals(arrayOf("*/*"), host.launchedWith)
    }

    @Test
    fun `a second pick is served after the first`() = runTest {
        val picker: FilePicker =
            createFilePicker(context, StubHost(resultUri = fileUri("a.txt", byteArrayOf(7))))

        val first: PickResult = picker.pick()
        val second: PickResult = picker.pick()

        assertIs<PickResult.Picked>(first)
        assertIs<PickResult.Picked>(second)
        assertTrue(directory.resolve("a.txt").exists())
    }
}
