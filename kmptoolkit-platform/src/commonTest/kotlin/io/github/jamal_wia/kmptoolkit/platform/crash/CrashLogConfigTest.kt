package io.github.jamal_wia.kmptoolkit.platform.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CrashLogConfigTest {

    @Test
    fun `defaults to a toolkit-namespaced file in the platform directory`() {
        val config = CrashLogConfig()

        assertEquals("kmptoolkit_crash_log.txt", config.fileName)
        assertNull(config.directoryPath)
    }

    @Test
    fun `rejects an empty file name`() {
        assertFailsWith<IllegalArgumentException> { CrashLogConfig(fileName = "") }
    }

    @Test
    fun `rejects a blank file name`() {
        assertFailsWith<IllegalArgumentException> { CrashLogConfig(fileName = "   ") }
    }
}
