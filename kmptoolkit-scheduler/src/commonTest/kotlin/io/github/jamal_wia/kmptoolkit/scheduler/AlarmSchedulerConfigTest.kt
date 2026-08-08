package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [AlarmSchedulerConfig]'s validation and the Android scheme derivation. Both matter because
 * a bad identifier here does not fail loudly at schedule time — it produces alarms that collide or
 * never arrive.
 */
class AlarmSchedulerConfigTest {

    @Test
    fun `default config uses the documented key names and derives its scheme`() {
        val config = AlarmSchedulerConfig()

        assertEquals("alarm_id", config.alarmIdKey)
        assertEquals("alarm_type", config.alarmTypeKey)
        assertEquals(null, config.alarmIntentScheme)
    }

    @Test
    fun `a blank id key is rejected`() {
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIdKey = "  ") }
    }

    @Test
    fun `a blank type key is rejected`() {
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmTypeKey = "") }
    }

    @Test
    fun `two identical keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AlarmSchedulerConfig(alarmIdKey = "key", alarmTypeKey = "key")
        }
    }

    @Test
    fun `a scheme that does not start with a letter is rejected`() {
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIntentScheme = "1alarm") }
    }

    @Test
    fun `a scheme containing an illegal character is rejected`() {
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIntentScheme = "my_alarm") }
    }

    @Test
    fun `a blank scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIntentScheme = "") }
    }

    @Test
    fun `a valid scheme is accepted and used verbatim`() {
        val config = AlarmSchedulerConfig(alarmIntentScheme = "my-app.alarms+v2")

        assertEquals("my-app.alarms+v2", config.resolveIntentScheme("com.example.app"))
    }

    @Test
    fun `an absent scheme is derived from the application id`() {
        assertEquals("com.example.app.alarm", AlarmSchedulerConfig().resolveIntentScheme("com.example.app"))
    }

    @Test
    fun `a derived scheme replaces characters a URI scheme cannot hold`() {
        val scheme: String = AlarmSchedulerConfig().resolveIntentScheme("com.example.my_app")

        assertEquals("com.example.my-app.alarm", scheme)
        assertTrue(VALID_SCHEME.matches(scheme), "derived scheme is not a valid URI scheme: $scheme")
    }

    @Test
    fun `a derived scheme starting with a digit is prefixed into validity`() {
        val scheme: String = AlarmSchedulerConfig().resolveIntentScheme("7eleven.app")

        assertEquals("a7eleven.app.alarm", scheme)
        assertTrue(VALID_SCHEME.matches(scheme), "derived scheme is not a valid URI scheme: $scheme")
    }

    @Test
    fun `a derived scheme stays valid for a non-ASCII application id`() {
        val scheme: String = AlarmSchedulerConfig().resolveIntentScheme("café.app")

        assertTrue(VALID_SCHEME.matches(scheme), "derived scheme is not a valid URI scheme: $scheme")
    }

    private companion object {
        val VALID_SCHEME = Regex("[a-zA-Z][a-zA-Z0-9+.\\-]*")
    }
}
