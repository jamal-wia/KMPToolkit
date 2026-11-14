package io.github.jamal_wia.kmptoolkit.files.testing

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** Round 5, Q4: every sample of docs 01-03 run against the fake (POSIX paths as strings). */
class DocsSamplesR5FakeTest {
    @Test
    fun `every sample of the overview and the getting started and the guide runs against the fake`() = runTest {
        val files: FakeFileAccess = FakeFileAccess()
        runGuideSamples(files, "/r5", { parent, child -> "$parent/$child" }) { path ->
            files.nowMillis = 7_200_000L
            files.setLastModified(path, 0L)
        }
    }

    @Test
    fun `every sample of the overview and the getting started and the guide runs against the case-insensitive fake`() = runTest {
        val files: FakeFileAccess = FakeFileAccess(caseSensitive = false)
        runGuideSamples(files, "/r5", { parent, child -> "$parent/$child" }) { path ->
            files.nowMillis = 7_200_000L
            files.setLastModified(path, 0L)
        }
    }
}
