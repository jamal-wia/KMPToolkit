package io.github.jamal_wia.kmptoolkit.platform.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The store is exercised against a real filesystem, because the properties that matter are
 * filesystem ones: that reading clears, that a half-written line costs only that line, and that a
 * store which cannot write says nothing rather than throwing — it runs inside a dying process.
 */
@RunWith(AndroidJUnit4::class)
class FileCrashLogStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File = File(context.filesDir, "crash-store-test").apply { mkdirs() }
    private val config = CrashLogConfig(fileName = "crash.txt", directoryPath = directory.path)
    private val file = File(directory, "crash.txt")
    private val store: CrashLogStore = createCrashLogStore(context, config)

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun record(message: String, timestampMs: Long = 1) = CrashRecord(
        timestampMs = timestampMs,
        threadName = "main",
        message = message,
        stackTrace = "at A\nat B",
    )

    @Test
    fun `reading an untouched store returns nothing`() {
        assertEquals(emptyList(), store.readAndClear())
    }

    @Test
    fun `a written record comes back unchanged`() {
        val written: CrashRecord = record("boom")

        store.write(written)

        assertEquals(listOf(written), store.readAndClear())
    }

    @Test
    fun `several crashes are kept in the order they happened`() {
        store.write(record("first", timestampMs = 1))
        store.write(record("second", timestampMs = 2))

        assertContentEquals(
            listOf(record("first", 1), record("second", 2)),
            store.readAndClear(),
        )
    }

    @Test
    fun `reading clears so the same crash is never reported twice`() {
        store.write(record("boom"))

        store.readAndClear()

        assertEquals(emptyList(), store.readAndClear())
        assertFalse(file.exists(), "the file must be gone once its records have been handed over")
    }

    @Test
    fun `a record survives a fresh store over the same file`() {
        store.write(record("boom"))

        // What actually happens in production: the process that wrote is gone, and a new one
        // constructs its own store over the same path.
        val next: CrashLogStore = createCrashLogStore(context, config)

        assertEquals(listOf(record("boom")), next.readAndClear())
    }

    @Test
    fun `a truncated final line costs that line and nothing else`() {
        store.write(record("survivor"))
        // A process killed mid-write leaves exactly this: a complete line, then a fragment.
        file.appendText("99\tmain\ttrunc")

        assertEquals(listOf(record("survivor")), store.readAndClear())
    }

    @Test
    fun `a corrupt line between two good ones is skipped`() {
        store.write(record("first", 1))
        file.appendText("this is not a record\n")
        store.write(record("second", 2))

        assertContentEquals(
            listOf(record("first", 1), record("second", 2)),
            store.readAndClear(),
        )
    }

    @Test
    fun `a file of nothing but corruption is cleared rather than re-read forever`() {
        file.writeText("garbage\nmore garbage\n")

        assertEquals(emptyList(), store.readAndClear())
        assertFalse(file.exists())
    }

    @Test
    fun `a multi-line stack trace round trips through the file`() {
        val deep: CrashRecord = record("boom").copy(
            stackTrace = (1..20).joinToString("\n") { frame -> "\tat com.example.Frame$frame" },
        )

        store.write(deep)

        assertEquals(listOf(deep), store.readAndClear())
    }

    @Test
    fun `writing creates a missing directory rather than losing the record`() {
        val nested = File(directory, "does/not/exist/yet")
        val nestedStore: CrashLogStore = createCrashLogStore(
            context,
            CrashLogConfig(fileName = "crash.txt", directoryPath = nested.path),
        )

        nestedStore.write(record("boom"))

        assertEquals(listOf(record("boom")), nestedStore.readAndClear())
    }

    @Test
    fun `a store that cannot write swallows the failure instead of throwing`() {
        // A file where the directory should be: every write below is impossible.
        val blocker = File(directory, "blocked")
        blocker.writeText("not a directory")
        val blocked: CrashLogStore = createCrashLogStore(
            context,
            CrashLogConfig(fileName = "crash.txt", directoryPath = File(blocker, "sub").path),
        )

        blocked.write(record("boom"))

        assertEquals(emptyList(), blocked.readAndClear())
    }

    @Test
    fun `the default store writes inside the app's private files directory`() {
        val default: CrashLogStore = createCrashLogStore(context)
        try {
            default.write(record("boom"))

            assertTrue(
                File(context.filesDir, CrashLogConfig.DEFAULT_FILE_NAME).exists(),
                "the default crash log must live in filesDir",
            )
        } finally {
            default.readAndClear()
        }
    }
}
