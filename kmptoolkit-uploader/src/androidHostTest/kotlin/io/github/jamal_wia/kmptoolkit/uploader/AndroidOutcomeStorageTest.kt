package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The files behind the Android outcome journal: where they live (no-backup storage), that a write is atomic
 * — a reader never sees a part of an entry — and that leftovers of a cut-short write are neither read nor
 * listed.
 */
@RunWith(AndroidJUnit4::class)
class AndroidOutcomeStorageTest {

    private val root: File = createTempDirectory("outcomes").toFile()
    private val dir = File(root, "outcomes")
    private val partial = File(root, "partial")
    private var now = 10_000_000L
    private val storage = AndroidOutcomeStorage(dir, partial, leftoverAgeMillis = 1_000L, now = { now })

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the journal lives under the no-backup directory`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertTrue(AndroidOutcomeStorage.forContext(context).write("item-1", byteArrayOf(1)))

        val expected = File(context.noBackupFilesDir, "kmptoolkit-uploader/outcomes/item-1")
        assertTrue(expected.isFile, "expected $expected")
        expected.delete()
    }

    @Test
    fun `write read list and delete`() {
        assertTrue(storage.write("b", byteArrayOf(1, 2)))
        assertTrue(storage.write("a", byteArrayOf(3)))

        assertContentEquals(byteArrayOf(1, 2), storage.read("b"))
        assertEquals(listOf("a", "b"), storage.itemIds())

        storage.delete("b")
        storage.delete("never-written")
        assertNull(storage.read("b"))
        assertEquals(listOf("a"), storage.itemIds())
    }

    @Test
    fun `a second write replaces the first whole`() {
        storage.write("a", ByteArray(100) { 1 })
        storage.write("a", byteArrayOf(9))

        assertContentEquals(byteArrayOf(9), storage.read("a"))
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "no leftover of a finished write")
    }

    @Test
    fun `a half-written file is never read or listed`() {
        partial.mkdirs()
        File(partial, "item-1.cut-short").writeBytes(byteArrayOf(1, 2, 3))

        assertNull(storage.read("item-1"))
        assertEquals(emptyList(), storage.itemIds())
    }

    @Test
    fun `only old leftovers are purged`() {
        partial.mkdirs()
        val old = File(partial, "old").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - 5_000L) }
        val young = File(partial, "young").also { it.writeBytes(byteArrayOf(1)); it.setLastModified(now - 10L) }

        storage.purgeLeftovers()

        assertFalse(old.exists())
        assertTrue(young.exists(), "a young one may belong to a write still running")
    }

    @Test
    fun `a write that cannot create its directory fails instead of throwing`() {
        root.mkdirs()
        dir.writeText("a file where the directory should be")

        assertFalse(storage.write("a", byteArrayOf(1)))
        assertNull(storage.read("a"))
    }

    @Test
    fun `a reader racing a writer sees only whole entries`() {
        val first = ByteArray(512 * 1024) { 1 }
        val second = ByteArray(512 * 1024) { 2 }
        storage.write("x", first)
        val running = AtomicBoolean(true)
        val torn = AtomicBoolean(false)
        val reader = thread {
            while (running.get()) {
                val read: ByteArray? = storage.read("x")
                if (read != null && !read.contentEquals(first) && !read.contentEquals(second)) torn.set(true)
            }
        }

        repeat(150) { index -> assertTrue(storage.write("x", if (index % 2 == 0) second else first)) }
        running.set(false)
        reader.join()

        assertFalse(torn.get(), "a reader saw a part of an entry")
    }
}
