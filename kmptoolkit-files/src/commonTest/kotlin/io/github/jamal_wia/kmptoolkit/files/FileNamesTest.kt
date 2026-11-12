package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.NameRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** N-1..N-8, C-1 and S-1: names, temp-name recognition, prefix validation, SQLite sidecars. */
class FileNamesTest {

    @Test
    fun `a plain name is non-empty not dot or dotdot and has no slash or NUL on every syntax`() {
        for (windows: Boolean in listOf(false, true)) {
            assertTrue(NameRules.isPlain("a.txt", windows))
            assertTrue(NameRules.isPlain("caf\u00e9", windows))
            for (name: String in listOf("", ".", "..", "a/b", "a\u0000b")) {
                assertFalse(NameRules.isPlain(name, windows), "'$name' windows=$windows")
            }
        }
    }

    @Test
    fun `the 255 byte limit counts UTF-8 bytes not characters`() {
        for (windows: Boolean in listOf(false, true)) {
            assertTrue(NameRules.isPlain("a".repeat(255), windows))
            assertFalse(NameRules.isPlain("a".repeat(256), windows))
            // U+00E9 is 2 bytes: 127 of them are 254 bytes, 128 are 256.
            assertTrue(NameRules.isPlain("\u00e9".repeat(127), windows))
            assertFalse(NameRules.isPlain("\u00e9".repeat(128), windows))
            // U+20AC is 3 bytes, a surrogate pair 4 bytes.
            assertTrue(NameRules.isPlain("\u20ac".repeat(85), windows))
            assertFalse(NameRules.isPlain("\u20ac".repeat(86), windows))
            assertTrue(NameRules.isPlain("\uD83D\uDE00".repeat(63) + "abc", windows))
            assertFalse(NameRules.isPlain("\uD83D\uDE00".repeat(64), windows))
        }
    }

    @Test
    fun `an unpaired surrogate is never a plain name`() {
        for (windows: Boolean in listOf(false, true)) {
            assertFalse(NameRules.isPlain("a\uD83D", windows))
            assertFalse(NameRules.isPlain("\uDE00a", windows))
            assertFalse(NameRules.isPlain("\uD83Da\uDE00", windows))
        }
    }

    @Test
    fun `windows reserved device names are recognized by the part before the first dot ignoring case`() {
        for (name: String in listOf("CON", "con", "Prn", "AUX", "nul", "COM1", "com9", "LPT0", "lpt5", "COM\u00b9", "LPT\u00b2", "COM\u00b3", "con.txt", "NUL.tar.gz", "COM0")) {
            assertTrue(FileNames.isWindowsReserved(name), name)
        }
        for (name: String in listOf("CONSOLE", "COM10", "COM", "LPT", "xcon", "a.con", "COMx", "")) {
            assertFalse(FileNames.isWindowsReserved(name), name)
        }
    }

    @Test
    fun `trailing spaces before the first dot do not hide a reserved device name`() {
        for (name: String in listOf("CON .txt", "con  .tar.gz", "COM1 .log", "LPT\u00b9 .x", "NUL ")) {
            assertTrue(FileNames.isWindowsReserved(name), "'$name'")
        }
        for (name: String in listOf("CONX.txt", "CO N.txt", "xCON.txt", "CON-1.txt")) {
            assertFalse(FileNames.isWindowsReserved(name), "'$name'")
        }
    }

    @Test
    fun `windows names reject forbidden characters control characters and trailing dot or space`() {
        for (name: String in listOf("a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b", "a\\b", "a\u0001b", "a\u001fb", "a.", "a ", "CON", "con.txt", "LPT0")) {
            assertFalse(NameRules.isPlain(name, true), "'$name'")
        }
        for (name: String in listOf("a<b", "a:b", "a\\b", "a.", "a ", "CON", "con.txt")) {
            assertTrue(NameRules.isPlain(name, false), "posix keeps '$name'")
        }
    }

    @Test
    fun `isPlainName and requirePlainName follow the host rules and the message names the parameter`() {
        assertTrue(FileNames.isPlainName("report.txt"))
        assertFalse(FileNames.isPlainName("a/b"))
        assertEquals("ok", FileNames.requirePlainName("ok"))
        val e: IllegalArgumentException = assertFailsWith { FileNames.requirePlainName("a/b", "directoryName") }
        assertTrue("directoryName" in (e.message ?: ""), e.message)
        val d: IllegalArgumentException = assertFailsWith { FileNames.requirePlainName("") }
        assertTrue("name" in (d.message ?: ""), d.message)
    }

    @Test
    fun `isTempName matches prefix digits tmp and nothing else`() {
        val p: String = FileAccessConfig.DEFAULT_TEMP_PREFIX
        assertTrue(FileNames.isTempName("${p}0.tmp"))
        assertTrue(FileNames.isTempName("${p}9223372036854775807.tmp"))
        assertTrue(FileNames.isTempName("${p}1234567890123456789.tmp"))
        assertTrue(FileNames.isTempName("x1.tmp", "x"))
    }

    @Test
    fun `isTempName rejects near misses`() {
        val p: String = FileAccessConfig.DEFAULT_TEMP_PREFIX
        assertFalse(FileNames.isTempName("x.tmp"))
        assertFalse(FileNames.isTempName("${p}12a.tmp"))
        assertFalse(FileNames.isTempName("${p}12345678901234567890.tmp"), "20 digits")
        assertFalse(FileNames.isTempName("$p.tmp"), "no digits")
        assertFalse(FileNames.isTempName("${p}12.tmp.bak"))
        assertFalse(FileNames.isTempName("${p}12.TMP"))
        assertFalse(FileNames.isTempName("${p}12"))
        assertFalse(FileNames.isTempName("${p}12.tmp", "other"))
        assertFalse(FileNames.isTempName(".tmp", ""), "an empty prefix still needs digits")
        assertFalse(FileNames.isTempName("\u0663.tmp", ""), "only ASCII digits")
    }

    @Test
    fun `MAX_TEMP_PREFIX_BYTES is 255 minus 19 digits minus the suffix and is the exact cap`() {
        assertEquals(FileNames.MAX_NAME_BYTES - 19 - ".tmp".length, FileAccessConfig.MAX_TEMP_PREFIX_BYTES)
        assertEquals(232, FileAccessConfig.MAX_TEMP_PREFIX_BYTES)
        FileAccessConfig("a".repeat(FileAccessConfig.MAX_TEMP_PREFIX_BYTES))
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("a".repeat(FileAccessConfig.MAX_TEMP_PREFIX_BYTES + 1)) }
        WriteOptions(tempPrefix = "a".repeat(FileAccessConfig.MAX_TEMP_PREFIX_BYTES))
        assertFailsWith<IllegalArgumentException> { WriteOptions(tempPrefix = "a".repeat(FileAccessConfig.MAX_TEMP_PREFIX_BYTES + 1)) }
    }

    @Test
    fun `FileAccessConfig accepts a prefix of 1 to 232 plain bytes`() {
        assertEquals(".kmptoolkit-", FileAccessConfig().tempPrefix)
        FileAccessConfig("a")
        FileAccessConfig("a".repeat(232))
        FileAccessConfig("\u00e9".repeat(116))
        FileAccessConfig("my-app.")
    }

    @Test
    fun `FileAccessConfig rejects an empty a too long and a non-plain prefix`() {
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("") }
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("a".repeat(233)) }
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("a/") }
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("a\u0000") }
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("\u00e9".repeat(117)) }
        assertFailsWith<IllegalArgumentException> { FileAccessConfig("a\uD83D") }
    }

    @Test
    fun `WriteOptions validates its temp prefix like the config`() {
        WriteOptions(tempPrefix = "x.")
        WriteOptions(tempPrefix = null)
        WriteOptions(tempPrefix = "a".repeat(232))
        assertFailsWith<IllegalArgumentException> { WriteOptions(tempPrefix = "") }
        assertFailsWith<IllegalArgumentException> { WriteOptions(tempPrefix = "a".repeat(233)) }
        assertFailsWith<IllegalArgumentException> { WriteOptions(tempPrefix = "a/b") }
        assertFailsWith<IllegalArgumentException> { WriteOptions().copy(tempPrefix = "") }
    }

    @Test
    fun `sidecarsOf lists wal shm journal in that order`() {
        assertEquals(listOf("/d/app.db-wal", "/d/app.db-shm", "/d/app.db-journal"), SqliteFiles.sidecarsOf("/d/app.db"))
    }
}
