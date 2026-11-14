package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

/**
 * Android half of the path parity table. The iOS half is red on this table (suspect R10-01, withheld from the
 * repository until the lead rules whether iOS or the documented sentence changes).
 */
@RunWith(AndroidJUnit4::class)
class AndroidRecordingFileSystemPathParityR10Test {

    @Test
    fun `resolve parentOf and isAbsolute answer the shared table`() {
        // The table is POSIX-shaped; a Windows host has its own separator (NOT VERIFIED (Windows)).
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val context: Context = ApplicationProvider.getApplicationContext()
        val observed: List<String> = RecordingFileSystemPathParityR10.observe(AndroidRecordingFileSystem(context))

        assertEquals(emptyList(), RecordingFileSystemPathParityR10.mismatches(observed), "Android differs from the contract table")
    }
}
