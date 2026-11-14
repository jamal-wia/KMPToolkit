package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Round 8 (hostile consumer, iOS): the multipart body writer fed with what a consumer can put into an UploadRequest:
 * sources that are not files, empty files, huge text values, many fields and names the guide says are escaped.
 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
class IosMultipartBodyRound8AdversarialTest {

    private val created: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        created.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private fun tempPath(name: String): String {
        val path: String = NSTemporaryDirectory() + "kmptoolkit_r8_" + name
        created += path
        return path
    }

    private fun writeText(path: String, text: String) {
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun readText(path: String): String? = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    @Test
    fun `a directory or an empty path or a missing file as a source fail the body and leave nothing behind`() {
        for ((index, source) in listOf(NSTemporaryDirectory(), "", "/", "/nonexistent/r8/a.bin").withIndex()) {
            val bodyPath: String = tempPath("body_bad_$index.tmp")

            val failure: String? = writeMultipartBody(
                bodyPath = bodyPath,
                boundary = "B",
                fields = listOf(UploadField.Text("before", "x"), UploadField.File("file", "a.bin", "text/plain", source)),
            )

            assertNotNull(failure, "source '$source' must fail the body")
            assertFalse(exists(bodyPath), "source '$source' left a body behind")
        }
    }

    @Test
    fun `an empty source file is an empty part and the body stays well formed`() {
        val source: String = tempPath("empty.bin")
        writeText(source, "")
        val bodyPath: String = tempPath("body_empty.tmp")

        val failure: String? = writeMultipartBody(bodyPath, "B", listOf(UploadField.File("f", "e.bin", "application/octet-stream", source)))

        assertNull(failure)
        assertEquals(
            "--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"e.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n\r\n--B--\r\n",
            readText(bodyPath),
        )
    }

    @Test
    fun `no fields at all is just the closing boundary`() {
        val bodyPath: String = tempPath("body_none.tmp")

        assertNull(writeMultipartBody(bodyPath, "B", emptyList()))

        assertEquals("--B--\r\n", readText(bodyPath))
    }

    @Test
    fun `names and file names and content types are escaped as documented and a text value goes out untouched`() {
        val source: String = tempPath("odd_source.bin")
        writeText(source, "payload")
        val bodyPath: String = tempPath("body_odd.tmp")
        val hostileText = "v\r\n--B--\r\nInjected: 1"

        val failure: String? = writeMultipartBody(
            bodyPath, "B",
            listOf(
                UploadField.Text("n\"q\r\nInjected: 1", hostileText),
                UploadField.File("f\"x", "a\"b\r\nc.bin", "text/plain\r\nX-Injected: 1", source),
            ),
        )

        assertNull(failure)
        val expected: String =
            "--B\r\nContent-Disposition: form-data; name=\"n%22q%0D%0AInjected: 1\"\r\n\r\n$hostileText\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"f%22x\"; filename=\"a%22b%0D%0Ac.bin\"\r\n" +
                "Content-Type: text/plainX-Injected: 1\r\n\r\npayload\r\n--B--\r\n"
        assertEquals(expected, readText(bodyPath))
    }

    @Test
    fun `five MiB of text and a thousand fields are written completely`() {
        val bodyPath: String = tempPath("body_big.tmp")
        val big: String = "t".repeat(5 * 1024 * 1024)
        val fields: List<UploadField> = listOf<UploadField>(UploadField.Text("big", big)) +
            (0 until 1_000).map { UploadField.Text("n$it", "v$it") }

        val failure: String? = writeMultipartBody(bodyPath, "B", fields)

        assertNull(failure)
        val body: String = assertNotNull(readText(bodyPath))
        val expectedLength: Int = fields.sumOf { field ->
            val text = field as UploadField.Text
            "--B\r\nContent-Disposition: form-data; name=\"${text.name}\"\r\n\r\n${text.value}\r\n".length
        } + "--B--\r\n".length
        assertEquals(expectedLength, body.length)
        assertTrue(body.endsWith("v999\r\n--B--\r\n"))
    }
}
