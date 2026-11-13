package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.test.Test
import kotlin.test.assertEquals

// path-hygiene-file: logical-paths the rules are exercised on both syntaxes with literal paths; no file system is touched

/** G12: `withoutTrailingSeparators` drops trailing separators and keeps a root as it is. */
class PathRulesTrailingSeparatorsR2FixTest {
    private val posix: PathRules = PathRules.Posix
    private val windows: PathRules = PathRules.Windows

    @Test
    fun `posix drops trailing separators and keeps the root`() {
        assertEquals("/a/b", posix.withoutTrailingSeparators("/a/b/"))
        assertEquals("/a", posix.withoutTrailingSeparators("/a//"))
        assertEquals("/", posix.withoutTrailingSeparators("/"))
        assertEquals("/", posix.withoutTrailingSeparators("//"))
        assertEquals("/a/.", posix.withoutTrailingSeparators("/a/."))
        assertEquals("/a/.", posix.withoutTrailingSeparators("/a/./"))
        assertEquals("/a/b", posix.withoutTrailingSeparators("/a/b"))
    }

    @Test
    fun `windows drops trailing separators of both kinds and keeps a drive root`() {
        assertEquals("C:\\d", windows.withoutTrailingSeparators("C:\\d\\"))
        assertEquals("C:/d", windows.withoutTrailingSeparators("C:/d//"))
        assertEquals("C:\\", windows.withoutTrailingSeparators("C:\\"))
        assertEquals("\\\\srv\\share\\d", windows.withoutTrailingSeparators("\\\\srv\\share\\d\\"))
    }
}
