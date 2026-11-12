package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the rules are exercised on both syntaxes with literal paths; no file system is touched

/** P-1..P-12: the lexical rules of both syntaxes, run on every host. */
class PathRulesTest {
    private val posix: PathRules = PathRules.Posix
    private val windows: PathRules = PathRules.Windows

    @Test
    fun `posix isAbsolute is true exactly for a slash-rooted path`() {
        for (path: String in listOf("/", "/a", "//a")) assertTrue(posix.isAbsolute(path), path)
        for (path: String in listOf("", "a", "a/b", "C:\\", "\\x", ".", "..")) assertFalse(posix.isAbsolute(path), path)
    }

    @Test
    fun `windows isAbsolute accepts drive and share roots in both spellings`() {
        for (path: String in listOf("C:\\", "C:/x", "c:\\x\\y", "\\\\s\\sh\\x", "//s/sh/x", "\\\\s\\sh")) {
            assertTrue(windows.isAbsolute(path), path)
        }
    }

    @Test
    fun `windows isAbsolute rejects drive-relative root-only server-only and empty paths`() {
        for (path: String in listOf("", "C:x", "C:", "\\x", "/x", "a", "\\\\s", "\\\\s\\", "\\\\")) {
            assertFalse(windows.isAbsolute(path), "'$path'")
        }
    }

    @Test
    fun `join puts exactly one separator at the joint`() {
        assertEquals("/a/b", posix.join("/a", "b"))
        assertEquals("/a/b", posix.join("/a/", "b"))
        assertEquals("/b", posix.join("/", "b"))
        assertEquals("C:\\a\\b", windows.join("C:\\a", "b"))
        assertEquals("C:\\a/b", windows.join("C:\\a/", "b"))
        assertEquals("C:\\b", windows.join("C:\\", "b"))
        assertEquals("a/b/c", posix.join("a", "b/c"))
    }

    @Test
    fun `join throws for an empty or absolute child`() {
        assertFailsWith<IllegalArgumentException> { posix.join("/a", "") }
        assertFailsWith<IllegalArgumentException> { posix.join("/a", "/b") }
        assertFailsWith<IllegalArgumentException> { windows.join("C:\\a", "D:\\b") }
        assertFailsWith<IllegalArgumentException> { windows.join("C:\\a", "") }
    }

    @Test
    fun `parentOf drops the last segment and ignores trailing separators`() {
        assertEquals("/a", posix.parentOf("/a/b"))
        assertEquals("/a", posix.parentOf("/a/b/"))
        assertEquals("/a", posix.parentOf("/a//b"))
        assertEquals("a", posix.parentOf("a/b"))
        assertEquals("/", posix.parentOf("/a"))
        assertEquals("C:\\a", windows.parentOf("C:\\a\\b"))
        assertEquals("C:/a", windows.parentOf("C:/a/b"))
        assertEquals("C:\\a", windows.parentOf("C:\\a\\b\\"))
    }

    @Test
    fun `parentOf is null for a root an empty path and a single relative segment`() {
        for (path: String in listOf("/", "//", "", "a", "a/")) assertNull(posix.parentOf(path), "'$path'")
        for (path: String in listOf("C:\\", "C:/", "", "a", "\\\\s\\sh", "\\\\s\\sh\\")) {
            assertNull(windows.parentOf(path), "'$path'")
        }
    }

    @Test
    fun `parentOf of a file directly under a root is that root`() {
        assertEquals("C:\\", windows.parentOf("C:\\a"))
        assertEquals("\\\\s\\sh", windows.parentOf("\\\\s\\sh\\x"))
        assertEquals("/", posix.parentOf("/x"))
    }

    @Test
    fun `normalizeLexically resolves dot and dotdot and repeated separators`() {
        assertEquals("/a/c", posix.normalizeLexically("/a/./b/../c"))
        assertEquals("/a/c", posix.normalizeLexically("/a//b/../c/"))
        assertEquals("a/c", posix.normalizeLexically("a/./b/../c"))
        assertEquals("a/c", posix.normalizeLexically("a/b/../c"))
    }

    @Test
    fun `dotdot above a root stays at the root and a relative path keeps its leading dotdot`() {
        assertEquals("/", posix.normalizeLexically("/.."))
        assertEquals("/a", posix.normalizeLexically("/../../a"))
        assertEquals("../../a", posix.normalizeLexically("../../a"))
        assertEquals("../a", posix.normalizeLexically("x/../../a"))
        assertEquals("C:\\a", windows.normalizeLexically("C:\\..\\a"))
    }

    @Test
    fun `an empty result is a single dot`() {
        assertEquals(".", posix.normalizeLexically(""))
        assertEquals(".", posix.normalizeLexically("a/.."))
        assertEquals(".", posix.normalizeLexically("./."))
        assertEquals(".", windows.normalizeLexically("a\\.."))
    }

    @Test
    fun `windows normalizeLexically accepts both separators and writes backslash`() {
        assertEquals("C:\\a\\b", windows.normalizeLexically("C:/a\\./b/"))
        assertEquals("C:\\", windows.normalizeLexically("C:/"))
        assertEquals("\\\\s\\sh\\x", windows.normalizeLexically("//s/sh/x"))
        assertEquals("C:x\\y", windows.normalizeLexically("C:x/y"))
    }

    @Test
    fun `resolveInside returns the normalized path for a relative that stays inside`() {
        assertEquals("/base/a/b", posix.resolveInside("/base", "a/./b"))
        assertEquals("/base/b", posix.resolveInside("/base", "a/../b"))
        assertEquals("/base", posix.resolveInside("/base", "."))
        assertEquals("/base", posix.resolveInside("/base/", ""))
        assertEquals("C:\\base\\a", windows.resolveInside("C:\\base", "a"))
    }

    @Test
    fun `resolveInside is null for an escape an absolute relative and a NUL`() {
        assertNull(posix.resolveInside("/base", ".."))
        assertNull(posix.resolveInside("/base", "a/../../x"))
        assertNull(posix.resolveInside("/base", "../base2"))
        assertNull(posix.resolveInside("/base", "/etc"))
        assertNull(posix.resolveInside("/base", "a\u0000b"))
        assertNull(windows.resolveInside("C:\\base", "D:\\x"))
        assertNull(windows.resolveInside("C:\\base", "\\x"))
        assertNull(windows.resolveInside("C:\\base", "..\\x"))
    }

    @Test
    fun `the public Paths functions apply the rules of the host`() {
        val host: PathRules = PathRules.host
        for (path: String in listOf("/", "/a", "a", "", "C:\\", "C:/x", "C:x", "\\x", "\\\\s\\sh\\x", "//s/sh/x")) {
            assertEquals(host.isAbsolute(path), Paths.isAbsolute(path), path)
        }
        assertEquals(host.normalizeLexically("a/./b"), Paths.normalizeLexically("a/./b"))
        assertEquals(host.parentOf("a/b"), Paths.parentOf("a/b"))
        assertEquals(host.join("a", "b"), Paths.join("a", "b"))
        assertEquals(host.resolveInside("a", "b"), Paths.resolveInside("a", "b"))
    }
}
