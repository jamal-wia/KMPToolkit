package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 13 mutation survivor M423. docs/kmptoolkit-scheduler/04-api-reference.md: the derived scheme
 * is `<applicationId>.alarm` "with characters a URI scheme cannot hold replaced by `-`". `+`, `.` and
 * `-` are legal scheme characters, so they stay: an alarm armed under 4.0.0 is found again only when
 * the derived scheme is byte for byte the same.
 */
class AlarmSchedulerDerivedSchemeKeepsPlusAdversarialTest {

    @Test
    fun `the derived scheme keeps plus dot and dash and replaces what a scheme cannot hold`() {
        val config: AlarmSchedulerConfig = AlarmSchedulerConfig()
        assertEquals("com.x+y-z.alarm", config.resolveIntentScheme("com.x+y-z"))
        assertEquals("a+b.alarm", config.resolveIntentScheme("a+b"))
        assertEquals("com.my-app_x.alarm".replace('_', '-'), config.resolveIntentScheme("com.my-app_x"))
        assertEquals("a++-.alarm", config.resolveIntentScheme("a++-"))
    }
}
