package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** SUSPECT G5: normalizing a relative Windows path can turn it into an absolute drive path (any host: PathRules.Windows). */
class WindowsRelativeStaysRelativeR1Test {
    @Test
    fun `normalizeLexically keeps a relative windows path relative`() {
        val rules: PathRules = PathRules.Windows
        for (path: String in listOf("./C:/x", ".\\c:\\share", "a/../C:/Windows", "./C:")) {
            val normalized: String = rules.normalizeLexically(path)
            assertFalse(rules.isAbsolute(path), "'$path' is relative")
            assertEquals(false, rules.isAbsolute(normalized), "'$path' normalizes to the absolute '$normalized'")
        }
    }

    @Test
    fun `the relative results are the exact documented values`() {
        val rules: PathRules = PathRules.Windows
        assertEquals(".\\C:\\x", rules.normalizeLexically("./C:/x"))
        assertEquals(".\\C:\\Windows", rules.normalizeLexically("a/../C:/Windows"))
        assertEquals(".\\C:", rules.normalizeLexically("./C:"))
    }

    @Test
    fun `normalizing a relative result again changes nothing`() {
        val rules: PathRules = PathRules.Windows
        for (path: String in listOf("./C:/x", "a/../C:/Windows", "./C:", ".\\C:\\x")) {
            val once: String = rules.normalizeLexically(path)
            assertEquals(once, rules.normalizeLexically(once), "'$path'")
        }
    }
}
