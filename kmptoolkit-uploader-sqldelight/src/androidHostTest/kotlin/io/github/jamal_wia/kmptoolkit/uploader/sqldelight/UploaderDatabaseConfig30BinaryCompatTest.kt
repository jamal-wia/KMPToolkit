package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Binary compatibility with 3.0.0: a library compiled against 3.0.0 that calls
 * `UploaderDatabaseConfig()` or `UploaderDatabaseConfig().copy()` links to the default-arguments
 * bridges `<init>(String, int, DefaultConstructorMarker)` and
 * `copy$default(UploaderDatabaseConfig, String, int, Object)`. Those bridges are looked up by their
 * exact JVM signature, so this test calls them reflectively the way such a call site does.
 */
class UploaderDatabaseConfig30BinaryCompatTest {

    @Test
    fun `the 3_0_0 default-arguments constructor bridge still exists and defaults the name to null`() {
        val marker: Class<*> = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val bridge = UploaderDatabaseConfig::class.java.getDeclaredConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            marker,
        )
        // Mask 1 = "argument 0 was omitted", exactly what `UploaderDatabaseConfig()` compiles to.
        val config = bridge.newInstance(null, 1, null)
        assertNull(config.name)
        assertFalse(config.recreateOnCorruption)
    }

    @Test
    fun `the 3_0_0 copy default bridge still exists and keeps the name when it is omitted`() {
        val bridge = UploaderDatabaseConfig::class.java.getDeclaredMethod(
            "copy\$default",
            UploaderDatabaseConfig::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val original = UploaderDatabaseConfig("com.example.q", recreateOnCorruption = true)
        val copy: UploaderDatabaseConfig = try {
            bridge.invoke(null, original, null, 1, null) as UploaderDatabaseConfig
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        assertEquals("com.example.q", copy.name)
        // The 3.0.0 copy had no such field; the flag of the receiver must survive, not silently reset.
        assertEquals(original.recreateOnCorruption, copy.recreateOnCorruption)
    }
}
