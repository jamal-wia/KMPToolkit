package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G, step 1 (Windows): aliases of one file that PathKey does not equate (8.3 short
 * name, junction, `subst` drive, administrative share) are "coordinated like another process" (04, 05): a holder of
 * the alias that does not share delete makes the replace fail with `AccessDenied` after the bounded retry (about
 * 210 ms), the target untouched and no temporary file left; once the holder closes, the write succeeds. A no-op on
 * hosts that are not Windows, and a no-op for an alias the host cannot create.
 */
class WindowsAliasR2Test {
    // path-hygiene-ignore: PH1 the separator is read only to detect a Windows host, where the alias attacks of this class run; it is never joined into a path
    private val windows: Boolean = File.separatorChar == '\\'
    private val files: FileAccess = createFileAccess()

    private fun cmd(script: String): String? =
        try {
            val process: Process = ProcessBuilder("cmd.exe", "/c", script).redirectErrorStream(true).start()
            val text: String = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) text.trim() else null
        } catch (_: Exception) {
            null
        }

    private fun withTarget(block: (root: File, target: File) -> Unit) {
        if (!windows) return
        val root: File = Files.createTempDirectory("kmptoolkit-alias-r2-with-a-long-name").toFile()
        try {
            val target: File = File(root, "a long file name for the short alias.txt")
            target.writeText("old")
            block(root, target)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertReplaceBlockedByHolderOf(alias: String, target: File, root: File) {
        FileInputStream(alias).use {
            val started: Long = System.nanoTime()
            val result: FileResult<Unit> = files.writeAtomically(target.path, "new".encodeToByteArray())
            val elapsedMillis: Long = (System.nanoTime() - started) / 1_000_000
            assertEquals(FileResult.Failure(FileError.AccessDenied(target.path)), result, "alias $alias")
            assertTrue(elapsedMillis in 100..3_000, "the retry budget is about 210 ms, took $elapsedMillis ms")
            assertEquals("old", target.readText())
            assertEquals(listOf(target.name), files.list(root.path).getOrNull(), "no temporary file is left")
        }
        assertEquals(FileResult.Success(Unit), files.writeAtomically(target.path, "new".encodeToByteArray()))
        assertEquals("new", target.readText())
    }

    @Test
    fun `a holder of the 8 dot 3 short name blocks the replace cleanly`() = withTarget { root: File, target: File ->
        val short: String? = cmd("for %I in (\"${target.path}\") do @echo %~sI")
        if (short == null || short.equals(target.path, ignoreCase = true) || !File(short).exists()) return@withTarget
        assertReplaceBlockedByHolderOf(short, target, root)
    }

    @Test
    fun `a holder through a junction blocks the replace cleanly`() = withTarget { root: File, target: File ->
        val junctionParent: File = Files.createTempDirectory("kmptoolkit-alias-r2-junction").toFile()
        val junction: File = File(junctionParent, "j")
        try {
            if (cmd("mklink /J \"${junction.path}\" \"${root.path}\"") == null) return@withTarget
            assertReplaceBlockedByHolderOf(File(junction, target.name).path, target, root)
        } finally {
            cmd("rmdir \"${junction.path}\"")
            junctionParent.deleteRecursively()
        }
    }

    @Test
    fun `a holder through a subst drive blocks the replace cleanly`() = withTarget { root: File, target: File ->
        val letter: Char = ('Z' downTo 'P').firstOrNull { c: Char -> !File("$c:\\").exists() } ?: return@withTarget
        if (cmd("subst $letter: \"${root.path}\"") == null) return@withTarget
        try {
            assertReplaceBlockedByHolderOf("$letter:\\${target.name}", target, root)
        } finally {
            cmd("subst $letter: /d")
        }
    }

    @Test
    fun `a holder through the administrative share blocks the replace cleanly`() = withTarget { root: File, target: File ->
        val drive: String = target.path.substring(0, 1)
        val share: String = "\\\\localhost\\$drive$" + target.path.substring(2)
        if (!File(share).exists()) return@withTarget
        assertReplaceBlockedByHolderOf(share, target, root)
    }

    @Test
    fun `a holder through FileInputStream of the plain path is the documented AccessDenied after the budget`() =
        withTarget { root: File, target: File ->
            assertReplaceBlockedByHolderOf(target.path, target, root)
        }

    /** Contest of the G6 rejection: readers of the short name through FileAccess only, a writer of the long name. Reported, not asserted. */
    @Test
    fun `readers through FileAccess of the short name and a writer of the long name are counted`() =
        withTarget { root: File, target: File ->
            val short: String? = cmd("for %I in (\"${target.path}\") do @echo %~sI")
            if (short == null || short.equals(target.path, ignoreCase = true) || !File(short).exists()) return@withTarget
            assertEquals(FileResult.Success(Unit), files.writeAtomically(target.path, ByteArray(8 * 1024 * 1024)))
            val stop: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
            val readers: List<Thread> = List(6) {
                Thread { while (!stop.get()) files.readBytes(short, 16L * 1024 * 1024) }.apply { isDaemon = true; start() }
            }
            var denied: Int = 0
            try {
                repeat(60) {
                    if (files.writeAtomically(target.path, ByteArray(8 * 1024 * 1024)) is FileResult.Failure) denied++
                }
            } finally {
                stop.set(true)
                readers.forEach { t: Thread -> t.join(10_000) }
            }
            println("ALIAS-CONTEST failed replaces through 8.3 alias readers: $denied of 60")
        }
}
