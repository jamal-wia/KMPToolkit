package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * G16 (low, Windows path syntax, lexical only; runs on every host through the internal PathRules.Windows).
 * Docs 04: normalizeLexically "Never changes whether a path is absolute or rooted". A path that starts with three or
 * more separators has an empty server name, so isAbsolute is false; the normalized form shifts the first two
 * segments into the server and share slots and is absolute.
 * Hit 1 of 1 (seed 40403, path //\B\.//e).
 */
class WindowsNormalizeAbsolutenessR4FixTest {
    @Test
    fun `three leading separators stay not absolute after normalizeLexically`() {
        for (path: String in listOf("///a/b", "\\\\\\a\\b", "//\\B\\.//e", "///a/b/c")) {
            val normalized: String = PathRules.Windows.normalizeLexically(path)
            assertEquals(
                PathRules.Windows.isAbsolute(path),
                PathRules.Windows.isAbsolute(normalized),
                "'$path' -> '$normalized'",
            )
        }
    }
}
