package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** P-13: the host differential, with every vector of the former core FilePathsTest kept. */
class JvmFilesTest {
    private val vectors: List<String> = listOf(
        "", ".", "..", "a", "a/b", "a/b/", "./a", "../a", "~", "~/a", "a:b",
        "/", "/a", "/a/b", "/a/../b",
        "C:\\", "C:\\data\\x", "c:/data/x", "\\\\server\\share\\x", "//s/sh/x", "C:data", "C:", "\\data", "\\x", "C:/x",
        "\\\\s\\sh\\x",
    )

    @Test
    fun `Paths isAbsolute agrees with java io File isAbsolute on every vector`() {
        for (vector: String in vectors) {
            assertEquals(File(vector).isAbsolute, Paths.isAbsolute(vector), "'$vector'")
        }
    }
}
