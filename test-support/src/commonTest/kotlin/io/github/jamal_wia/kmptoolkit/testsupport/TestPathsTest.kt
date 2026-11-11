package io.github.jamal_wia.kmptoolkit.testsupport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Both syntaxes run on every machine: this is how macOS covers the converter's Windows behaviour. */
class TestPathsTest {
    private val win: PathSyntax = PathSyntax.WINDOWS
    private val posix: PathSyntax = PathSyntax.POSIX

    // -- join ---------------------------------------------------------------------------------

    @Test
    fun joinBuildsNativeWindowsPaths() {
        assertEquals("C:\\base\\tmp\\model-1.tmp", TestPaths.join("C:\\base", "tmp/model-1.tmp", win))
        assertEquals("C:\\base\\a", TestPaths.join("C:/base/", "a", win))
        assertEquals("C:\\a", TestPaths.join("C:\\", "a", win))
        assertEquals("\\\\srv\\share\\a\\b", TestPaths.join("\\\\srv\\share", "a/b", win))
    }

    @Test
    fun joinBuildsNativePosixPaths() {
        assertEquals("/base/tmp/model-1.tmp", TestPaths.join("/base", "tmp/model-1.tmp", posix))
        assertEquals("/a", TestPaths.join("/", "a", posix))
        assertEquals("rel/a", TestPaths.join("rel/", "a", posix))
    }

    @Test
    fun joinDropsEmptySegmentsAndKeepsDots() {
        assertEquals("/b/a/c", TestPaths.join("/b", "a//c/", posix))
        assertEquals("/b/./a/../c", TestPaths.join("/b", "./a/../c", posix))
        assertEquals("C:\\b\\..\\c", TestPaths.join("C:\\b", "../c", win))
    }

    @Test
    fun joinOfNothingReturnsTheBase() {
        assertEquals("/b", TestPaths.join("/b/", "", posix))
        assertEquals("C:\\", TestPaths.join("C:\\", "", win))
    }

    @Test
    fun joinRejectsAnAbsoluteLogicalPath() {
        assertFailsWith<IllegalArgumentException> { TestPaths.join("/b", "/etc", posix) }
        assertFailsWith<IllegalArgumentException> { TestPaths.join("C:\\b", "/etc", win) }
        assertFailsWith<IllegalArgumentException> { TestPaths.join("C:\\b", "\\etc", win) }
        assertFailsWith<IllegalArgumentException> { TestPaths.join("C:\\b", "D:x", win) }
    }

    @Test
    fun aDriveLikeLogicalSegmentIsFineOnPosix() {
        assertEquals("/b/D:x", TestPaths.join("/b", "D:x", posix))
    }

    @Test
    fun joinDoesNotTurnABackslashIntoASeparatorOnPosix() {
        assertEquals("/b/a\\c", TestPaths.join("/b", "a\\c", posix))
    }

    // -- parent / fileName --------------------------------------------------------------------

    @Test
    fun parentFollowsJavaIoFile() {
        assertEquals("C:\\a", TestPaths.parent("C:\\a\\b", win))
        assertEquals("C:\\", TestPaths.parent("C:\\a", win))
        assertNull(TestPaths.parent("C:\\", win))
        assertEquals("\\\\srv\\share\\", TestPaths.parent("\\\\srv\\share\\a", win))
        assertNull(TestPaths.parent("name", win))
        assertEquals("/a", TestPaths.parent("/a/b", posix))
        assertEquals("/", TestPaths.parent("/a", posix))
        assertNull(TestPaths.parent("/", posix))
        assertNull(TestPaths.parent("name", posix))
        assertEquals("a", TestPaths.parent("a/b/", posix))
    }

    @Test
    fun parentOfAWindowsPathOnPosixIsNull() {
        // A backslash is an ordinary character there: the whole string is one file name.
        assertNull(TestPaths.parent("C:\\a\\b", posix))
    }

    @Test
    fun fileNameIsTheLastSegment() {
        assertEquals("b.bin", TestPaths.fileName("C:\\a\\b.bin", win))
        assertEquals("b.bin", TestPaths.fileName("/a/b.bin", posix))
        assertEquals("", TestPaths.fileName("C:\\", win))
        assertEquals("", TestPaths.fileName("/", posix))
        assertEquals("C:\\a\\b.bin", TestPaths.fileName("C:\\a\\b.bin", posix))
    }

    // -- toLogical ----------------------------------------------------------------------------

    @Test
    fun toLogicalOnWindowsConvertsSeparatorsAndKeepsTheDrive() {
        assertEquals("a/b", TestPaths.toLogical("a\\b", win))
        assertEquals("C:/x/y", TestPaths.toLogical("C:\\x\\y", win))
        assertEquals("C:/x", TestPaths.toLogical("\\\\?\\C:\\x", win))
        assertEquals("//s/x", TestPaths.toLogical("\\\\?\\UNC\\s\\x", win))
        assertEquals("//s/x", TestPaths.toLogical("\\\\s\\x", win))
    }

    @Test
    fun toLogicalOnPosixIsTheIdentity() {
        assertEquals("a\\b", TestPaths.toLogical("a\\b", posix))
        assertEquals("/a/b", TestPaths.toLogical("/a/b", posix))
    }

    @Test
    fun toLogicalLetsAWindowsNativePathEndWithALogicalOne() {
        val native: String = TestPaths.join("C:\\Users\\x\\files", "tmp/model-1.tmp", win)
        assertTrue(TestPaths.toLogical(native, win).endsWith("/files/tmp/model-1.tmp"))
    }

    // -- normalize ----------------------------------------------------------------------------

    @Test
    fun normalizeResolvesDotsLexically() {
        assertEquals("C:\\b", TestPaths.normalize("C:\\a\\..\\b", win))
        assertEquals("C:\\a\\b", TestPaths.normalize("C:/a/./b/", win))
        assertEquals("/b", TestPaths.normalize("/a/../b", posix))
        assertEquals("a/b", TestPaths.normalize("./a//b", posix))
    }

    @Test
    fun normalizeDropsDotDotAtARootButKeepsItInARelativePath() {
        assertEquals("C:\\", TestPaths.normalize("C:\\..\\..", win))
        assertEquals("/", TestPaths.normalize("/../..", posix))
        assertEquals("\\\\srv\\share\\", TestPaths.normalize("\\\\srv\\share\\..", win))
        assertEquals("../a", TestPaths.normalize("../a", posix))
        assertEquals("..\\..\\a", TestPaths.normalize("..\\x\\..\\..\\a", win))
        assertEquals(".", TestPaths.normalize("a/..", posix))
    }

    @Test
    fun normalizeKeepsADriveRelativePathRelative() {
        assertEquals("C:..\\a", TestPaths.normalize("C:..\\a", win))
    }

    @Test
    fun normalizeTreatsABackslashAsANameOnPosix() {
        assertEquals("a\\..\\b", TestPaths.normalize("a\\..\\b", posix))
    }

    // -- relativeLogical / isInside -----------------------------------------------------------

    @Test
    fun relativeLogicalFindsThePathBelowTheBase() {
        assertEquals("a/b.bin", TestPaths.relativeLogical("C:\\root", "C:\\root\\a\\b.bin", win))
        assertEquals("a/b.bin", TestPaths.relativeLogical("/root/", "/root/a/b.bin", posix))
        assertEquals("", TestPaths.relativeLogical("/root", "/root/", posix))
        assertEquals("b", TestPaths.relativeLogical("C:\\root", "C:\\root\\a\\..\\b", win))
    }

    @Test
    fun relativeLogicalIsNullOutsideTheBase() {
        assertNull(TestPaths.relativeLogical("C:\\root", "C:\\root2\\a", win))
        assertNull(TestPaths.relativeLogical("C:\\root", "C:\\root\\..\\x", win))
        assertNull(TestPaths.relativeLogical("C:\\root", "D:\\root\\a", win))
        assertNull(TestPaths.relativeLogical("/root", "/root/../x", posix))
        assertNull(TestPaths.relativeLogical("/root/a", "/root", posix))
    }

    @Test
    fun caseIsIgnoredOnWindowsButExactOnPosix() {
        assertEquals("A", TestPaths.relativeLogical("c:\\ROOT", "C:\\root\\A", win))
        assertNull(TestPaths.relativeLogical("/ROOT", "/root/A", posix))
    }

    @Test
    fun isInsideIsStrict() {
        assertTrue(TestPaths.isInside("C:\\root", "C:\\root\\a", win))
        assertFalse(TestPaths.isInside("C:\\root", "C:\\root", win))
        assertFalse(TestPaths.isInside("C:\\root", "C:\\rootx\\a", win))
        assertFalse(TestPaths.isInside("/root", "/root/../etc", posix))
        assertTrue(TestPaths.isInside("/root", "/root/a/../b", posix))
    }

    // -- TestHost helpers that are pure ---------------------------------------------------------

    @Test
    fun windowsFileNameRules() {
        for (bad: String in listOf("a:b", "a\\b", "a/b", "a<b", "a>b", "a\"b", "a|b", "a?b", "a*b", "a\nb", "a\tb",
            "", "CON", "con", "NUL.txt", "COM1", "lpt9", "a.", "a ")) {
            assertFalse(TestHost.isValidHostFileName(bad, win), "should be invalid on Windows: '$bad'")
        }
        for (good: String in listOf("a", "a.b", "COM10", "CONSOLE", "model 1.mp4", "\u00e9", "a b")) {
            assertTrue(TestHost.isValidHostFileName(good, win), "should be valid on Windows: '$good'")
        }
    }

    @Test
    fun posixFileNameRules() {
        assertFalse(TestHost.isValidHostFileName("", posix))
        assertFalse(TestHost.isValidHostFileName("a/b", posix))
        assertFalse(TestHost.isValidHostFileName("a\u0000b", posix))
        assertTrue(TestHost.isValidHostFileName("a\\b", posix))
        assertTrue(TestHost.isValidHostFileName("a:b", posix))
        assertTrue(TestHost.isValidHostFileName("a\nb", posix))
        assertTrue(TestHost.isValidHostFileName("CON", posix))
    }

    @Test
    fun theHostLimitMatchesTheHostSyntax() {
        val limit: Int? = TestHost.maxNativePathLength
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) assertEquals(259, limit) else assertNull(limit)
    }
}
