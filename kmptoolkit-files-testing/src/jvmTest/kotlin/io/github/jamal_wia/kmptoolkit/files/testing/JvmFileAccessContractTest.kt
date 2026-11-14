package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import kotlin.test.Test

/** The published contract against the real desktop JVM implementation (also run on the Windows PC). */
class JvmFileAccessContractTest {
    @Test
    fun `the real JVM FileAccess keeps the whole contract`() {
        FileAccessContract { realFixture(createFileAccess()) }.verifyAll()
    }
}
