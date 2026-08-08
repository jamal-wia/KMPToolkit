package io.github.jamal_wia.kmptoolkit.logging

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The threshold check in [createLoggerFactory] is a plain `>=` on this enum, so its declaration
 * order is part of the module's contract, not an implementation detail.
 */
class LogLevelTest {

    @Test
    fun `levels are declared least to most severe`() {
        assertContentEquals(
            listOf(
                LogLevel.VERBOSE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
            ),
            LogLevel.entries,
        )
    }

    @Test
    fun `comparison follows severity`() {
        assertTrue(LogLevel.VERBOSE < LogLevel.DEBUG)
        assertTrue(LogLevel.DEBUG < LogLevel.INFO)
        assertTrue(LogLevel.INFO < LogLevel.WARN)
        assertTrue(LogLevel.WARN < LogLevel.ERROR)
    }
}
