package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The migration pass over a fake file system, so the interleavings a real disk cannot be forced into are exact. */
class LegacyTempMigrationsTest {

    private val layout = UnitFileLayout(caseInsensitive = false)
    private val unit: DownloadUnit = object : DownloadUnit {
        override val id: String = "models/v1"
        override val apiPath: String = "/x"
        override val relativePath: String = "resources/x.bin"
        override val tempExtension: String = "tmp"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = "g"
            override val units: List<DownloadUnit> = emptyList()
        }
    }
    private val names: UnitFileLayout.Names = layout.namesOf(unit)

    @AfterTest
    fun reset() {
        LegacyTempMigrations.resetForTests()
    }

    @Test
    fun `a destination that appears after the existence check is never replaced`() {
        val files = RacingFiles(legacy = "base/tmp/models/v1.tmp", destination = "base/${names.partial}")

        LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger)

        assertEquals("new transfer", files.contents[files.destination], "the destination written meanwhile must survive")
        assertFalse("base/tmp/models/v1.tmp" in files.contents, "the stale legacy file is dropped")
    }

    @Test
    fun `a failed move with no destination keeps the legacy file`() {
        val files = RacingFiles(legacy = "base/tmp/models/v1.tmp", destination = "base/${names.partial}", racing = false)
        files.failMoves = true

        LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger)

        assertTrue("base/tmp/models/v1.tmp" in files.contents)
        assertFalse(files.destination in files.contents)
    }

    @Test
    fun `the record of finished units can be reset so a test may run the pass again`() {
        val files = RacingFiles(legacy = "base/tmp/models/v1.tmp", destination = "base/${names.partial}", racing = false)
        LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger)
        assertTrue(files.destination in files.contents)

        files.contents["base/tmp/models/v1.tmp"] = "again"
        LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger)
        assertTrue("base/tmp/models/v1.tmp" in files.contents, "a unit already done is skipped")

        LegacyTempMigrations.resetForTests()
        files.contents.remove(files.destination)
        LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger)
        assertFalse("base/tmp/models/v1.tmp" in files.contents, "after the reset the pass runs again")
    }

    /**
     * A file system where, with [racing], a transfer creates the destination between the pass's
     * existence check and its move, which only a no-replace move survives.
     */
    private class RacingFiles(
        legacy: String,
        val destination: String,
        private val racing: Boolean = true,
    ) : LegacyTempMigrations.Files {
        val contents: MutableMap<String, String> = mutableMapOf(legacy to "legacy bytes")
        var failMoves: Boolean = false
        private var destinationChecks: Int = 0

        override fun exists(path: String): Boolean {
            if (path == destination && racing && destinationChecks++ == 0) return false // the stale view
            return path in contents
        }

        override fun renameNoReplace(from: String, to: String): Boolean {
            if (racing && to == destination && destination !in contents) contents[destination] = "new transfer"
            if (failMoves || to in contents) return false
            contents[to] = contents.remove(from) ?: return false
            return true
        }

        override fun delete(path: String) {
            contents.remove(path)
        }

        override fun createParentDirectory(path: String) = Unit
    }
}
