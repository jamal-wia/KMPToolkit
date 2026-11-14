package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Round 1 of unit G, G9: `resolveInside` never lets the result climb above a relative base, and a base of `.` works. */
class ResolveInsideRelativeBaseR1FixTest {

    private fun forBoth(check: (PathRules, String) -> Unit) {
        check(PathRules.Posix, "/")
        check(PathRules.Windows, "\\")
    }

    @Test
    fun `a result that climbs above a relative base is null`() = forBoth { rules, _ ->
        assertNull(rules.resolveInside("..", "../b"))
        assertNull(rules.resolveInside("../a", "../../b"))
        assertNull(rules.resolveInside("a", "../b"))
        assertNull(rules.resolveInside("a/b", "../../.."))
    }

    @Test
    fun `a base of dot resolves what stays inside it`() = forBoth { rules, separator ->
        assertEquals("x", rules.resolveInside(".", "x"))
        assertEquals("x", rules.resolveInside("a/..", "x"))
        assertEquals("x${separator}y", rules.resolveInside(".", "x/y"))
        assertNull(rules.resolveInside(".", "../x"))
    }

    @Test
    fun `a relative base that begins with dot dot still holds what stays inside`() = forBoth { rules, separator ->
        assertEquals("..${separator}b", rules.resolveInside("..", "b"))
        assertEquals("..${separator}a${separator}c", rules.resolveInside("../a", "b/../c"))
    }

    @Test
    fun `absolute bases answer as before`() = forBoth { rules, separator ->
        val root: String = if (rules === PathRules.Posix) "/" else "C:\\"
        assertEquals("${root}a${separator}b", rules.resolveInside("${root}a", "b"))
        assertEquals("${root}a", rules.resolveInside("${root}a", "b/.."))
        assertNull(rules.resolveInside("${root}a", "../b"))
        assertNull(rules.resolveInside("${root}a", "/b"))
    }
}
