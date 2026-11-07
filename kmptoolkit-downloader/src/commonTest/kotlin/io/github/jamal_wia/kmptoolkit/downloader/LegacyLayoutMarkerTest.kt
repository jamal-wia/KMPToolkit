package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one-time per-base layout marker that limits the 3.0.0 temp migration to files that existed
 * before this version first used the base, over an in-memory file system.
 */
class LegacyLayoutMarkerTest {

    private val marker = "base/${UnitFileLayout.LEGACY_LAYOUT_MARKER}"

    @AfterTest
    fun reset() {
        LegacyTempMigrations.resetForTests()
    }

    @Test
    fun `a base with no tmp entries is fresh and nothing is ever migrated`() {
        val files = MemFiles()

        val cutoff = LegacyTempMigrations.ensureLayout("base", files, NoopLogger)

        assertEquals(LegacyTempMigrations.Cutoff.None, cutoff)
        assertEquals("fresh", files.contents[marker])
    }

    @Test
    fun `a tmp directory holding only the storage's own directory is still fresh`() {
        val files = MemFiles()
        files.contents["base/tmp/kmptoolkit-internal/trash/1/x"] = "x"

        assertEquals(LegacyTempMigrations.Cutoff.None, LegacyTempMigrations.ensureLayout("base", files, NoopLogger))
        assertEquals("fresh", files.contents[marker])
    }

    @Test
    fun `a base that already holds files in tmp is upgraded and the marker time is the cutoff`() {
        val files = MemFiles()
        files.contents["base/tmp/x/y.tmp"] = "partial"
        files.clock = 5_000L

        val cutoff = LegacyTempMigrations.ensureLayout("base", files, NoopLogger)

        assertEquals("upgraded", files.contents[marker])
        assertEquals(5_000L, assertIs<LegacyTempMigrations.Cutoff.Before>(cutoff).millis)
    }

    @Test
    fun `a caller that loses the creation race reads the winner's value`() {
        val files = MemFiles()
        files.winnerWritesFirst = "upgraded"
        files.clock = 7_000L

        val cutoff = LegacyTempMigrations.ensureLayout("base", files, NoopLogger)

        assertEquals(7_000L, assertIs<LegacyTempMigrations.Cutoff.Before>(cutoff).millis)
        assertEquals("upgraded", files.contents[marker])
    }

    @Test
    fun `unknown content in the marker means nothing is migrated`() {
        val files = MemFiles()
        files.contents[marker] = "garbage"
        files.mtimes[marker] = 1L

        assertEquals(LegacyTempMigrations.Cutoff.None, LegacyTempMigrations.ensureLayout("base", files, NoopLogger))
    }

    @Test
    fun `a marker that cannot be written means nothing is migrated with one warning and no throw`() {
        val files = MemFiles()
        files.contents["base/tmp/x/y.tmp"] = "partial"
        files.failWrites = true
        val logger = RecordingLogger()

        repeat(3) {
            assertEquals(LegacyTempMigrations.Cutoff.None, LegacyTempMigrations.ensureLayout("base", files, logger))
        }

        assertEquals(1, logger.warnings, "one warning per base and process")
        assertEquals(1, files.writeAttempts, "a failure is not retried in a loop")
    }

    @Test
    fun `a throwing file system is treated like an unwritable marker`() {
        val files = MemFiles()
        files.throwing = true

        assertEquals(LegacyTempMigrations.Cutoff.None, LegacyTempMigrations.ensureLayout("base", files, RecordingLogger()))
    }

    @Test
    fun `the decision is made once per base and later files do not change it`() {
        val files = MemFiles()
        val first = LegacyTempMigrations.ensureLayout("base", files, NoopLogger)
        files.contents["base/tmp/late.tmp"] = "x"

        assertEquals(first, LegacyTempMigrations.ensureLayout("base", files, NoopLogger))
        assertEquals("fresh", files.contents[marker])
    }

    @Test
    fun `only a legacy file strictly older than the marker is moved and none under a None cutoff`() {
        val layout = UnitFileLayout(caseInsensitive = false)
        val unit: DownloadUnit = object : DownloadUnit {
            override val id: String = "m/v1"
            override val apiPath: String = "/x"
            override val relativePath: String = "files/v1.bin"
            override val tempExtension: String = "tmp"
            override val group: ResourceGroup = object : ResourceGroup {
                override val key: String = "g"
                override val units: List<DownloadUnit> = emptyList()
            }
        }
        val names: UnitFileLayout.Names = layout.namesOf(unit)
        val cases: List<Triple<String, LegacyTempMigrations.Cutoff, Long?>> = listOf(
            Triple("older", LegacyTempMigrations.Cutoff.Before(1_000L), 999L),
            Triple("equal", LegacyTempMigrations.Cutoff.Before(1_000L), 1_000L),
            Triple("newer", LegacyTempMigrations.Cutoff.Before(1_000L), 2_000L),
            Triple("unknown time", LegacyTempMigrations.Cutoff.Before(1_000L), null),
            Triple("none", LegacyTempMigrations.Cutoff.None, 1L),
        )
        for ((label: String, cutoff: LegacyTempMigrations.Cutoff, mtime: Long?) in cases) {
            LegacyTempMigrations.resetForTests()
            val files = MemFiles()
            files.contents["base/tmp/m/v1.tmp"] = "bytes"
            files.contents["base/tmp/expect/m/v1.sha256"] = "sha256:00"
            if (mtime != null) files.mtimes["base/tmp/m/v1.tmp"] = mtime else files.mtimes.remove("base/tmp/m/v1.tmp")

            LegacyTempMigrations.migrateOnce("base", unit, layout, names, files, NoopLogger, cutoff)

            val moved: Boolean = "base/${names.partial}" in files.contents
            assertEquals(label == "older", moved, label)
            assertEquals(!moved, "base/tmp/m/v1.tmp" in files.contents, label)
            assertTrue("base/${names.record}" in files.contents, "the record is not gated ($label)")
        }
    }

    private class RecordingLogger : Logger {
        override val tag: String = "test"
        var warnings: Int = 0

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            message()
            if (level == LogLevel.WARN) warnings++
        }
    }

    private class MemFiles : LegacyTempMigrations.Files {
        val contents: MutableMap<String, String> = mutableMapOf()
        val mtimes: MutableMap<String, Long> = mutableMapOf()
        var clock: Long = 1_000L
        var failWrites: Boolean = false
        var throwing: Boolean = false
        var winnerWritesFirst: String? = null
        var writeAttempts: Int = 0

        override fun exists(path: String): Boolean = path in contents

        override fun renameNoReplace(from: String, to: String): Boolean {
            if (to in contents) return false
            contents[to] = contents.remove(from) ?: return false
            mtimes[to] = mtimes.remove(from) ?: clock
            return true
        }

        override fun delete(path: String) {
            contents.remove(path)
            mtimes.remove(path)
        }

        override fun createParentDirectory(path: String) = Unit

        override fun lastModifiedMillis(path: String): Long? = if (path in contents) mtimes[path] else null

        override fun hasEntriesOtherThan(directory: String, name: String): Boolean {
            if (throwing) error("disk gone")
            return contents.keys.any { key: String ->
                key.startsWith("$directory/") && key.removePrefix("$directory/").substringBefore('/') != name
            }
        }

        override fun readText(path: String): String? = contents[path]

        override fun writeNew(path: String, text: String): Boolean {
            writeAttempts++
            if (failWrites) return false
            val winner: String? = winnerWritesFirst
            if (winner != null) {
                winnerWritesFirst = null
                contents[path] = winner
                mtimes[path] = clock
                return false
            }
            if (path in contents) return false
            contents[path] = text
            mtimes[path] = clock
            return true
        }
    }
}
