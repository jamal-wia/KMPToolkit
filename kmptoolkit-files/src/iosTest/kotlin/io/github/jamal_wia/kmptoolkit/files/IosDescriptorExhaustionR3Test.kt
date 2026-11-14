@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EMFILE
import platform.posix.F_GETFD
import platform.posix.O_RDONLY
import platform.posix.RLIMIT_NOFILE
import platform.posix.close
import platform.posix.fcntl
import platform.posix.getrlimit
import platform.posix.open
import platform.posix.rlimit
import platform.posix.setrlimit

/**
 * Round 3 of the breaker for unit G, iOS side of the descriptor-exhaustion check (the JVM twin is
 * RealVolumeFailureR3Test): the soft descriptor limit is lowered to what is open now plus a few, the table is filled,
 * and every function must answer a typed `Io` carrying EMFILE (never throw, never hang), leaving nothing half done.
 * The limit is always restored and every descriptor closed before any assertion runs.
 */
class IosDescriptorExhaustionR3Test {

    private fun join(parent: String, child: String): String = parent.trimEnd('/') + "/" + child

    @Test
    fun `an exhausted descriptor table makes the calls fail with a typed Io carrying EMFILE`() {
        val root: String = TestEnv.newRoot()
        val files: FileAccess = createFileAccess()
        val file: String = join(root, "f")
        try {
            assertTrue(files.createDirectories(join(root, "d")).isSuccess)
            assertTrue(files.writeAtomically(file, "old".encodeToByteArray()).isSuccess)
            assertTrue(files.writeAtomically(join(root, "d/a"), "a".encodeToByteArray()).isSuccess)
            val results: MutableMap<String, Any?> = linkedMapOf()
            val thrown: MutableMap<String, Throwable> = linkedMapOf()
            val held: MutableList<Int> = mutableListOf()
            var savedSoft: ULong = 0u
            var savedHard: ULong = 0u
            memScoped {
                val limit: rlimit = alloc<rlimit>()
                check(getrlimit(RLIMIT_NOFILE.convert(), limit.ptr) == 0)
                savedSoft = limit.rlim_cur
                savedHard = limit.rlim_max
                var highest: Int = 2
                for (fd: Int in 0 until minOf(savedSoft, 8192uL).toInt()) if (fcntl(fd, F_GETFD) != -1) highest = fd
                limit.rlim_cur = (highest + 1 + 6).toULong()
                check(setrlimit(RLIMIT_NOFILE.convert(), limit.ptr) == 0) { "cannot lower the descriptor limit" }
                try {
                    while (held.size < 4096) {
                        val fd: Int = open("/dev/null", O_RDONLY)
                        if (fd < 0) break
                        held += fd
                    }
                    fun record(name: String, call: () -> Any?) {
                        try {
                            results[name] = call()
                        } catch (t: Throwable) {
                            thrown[name] = t
                        }
                    }
                    record("readBytes") { files.readBytes(file, 100) }
                    record("write") { files.writeAtomically(file, "new".encodeToByteArray()) }
                    record("writeNew") { files.writeAtomically(join(root, "g"), "new".encodeToByteArray()) }
                    record("list") { files.list(join(root, "d")) }
                    record("deleteRecursively") { files.deleteRecursively(join(root, "d")) }
                    record("sweep") { files.sweep(join(root, "d"), 0L, CancellationCheck.Never) { true } }
                    record("treeSize") { files.treeSize(root) }
                    record("metadata") { files.metadata(file) }
                } finally {
                    for (fd: Int in held) close(fd)
                    limit.rlim_cur = savedSoft
                    limit.rlim_max = savedHard
                    setrlimit(RLIMIT_NOFILE.convert(), limit.ptr)
                }
            }
            assertTrue(held.size > 0, "no descriptor limit reached")
            assertEquals(emptyMap(), thrown, "calls that threw")
            for (name: String in listOf("readBytes", "write", "writeNew", "list", "deleteRecursively", "sweep")) {
                val error: FileError = assertNotNull(
                    (results[name] as? FileResult<*>)?.errorOrNull(),
                    "$name under EMFILE: ${results[name]}",
                )
                assertTrue(error is FileError.Io && error.platformCode == EMFILE, "$name under EMFILE: $error")
            }
            assertEquals("old", files.readBytes(file, 100).getOrNull()?.decodeToString())
            assertEquals(emptyList(), (files.list(root).getOrNull() ?: emptyList()).filter { FileNames.isTempName(it) })
            assertEquals("a", files.readBytes(join(root, "d/a"), 100).getOrNull()?.decodeToString())
            assertTrue(files.writeAtomically(file, "new".encodeToByteArray()).isSuccess)
        } finally {
            TestEnv.deleteTree(root)
        }
    }
}
