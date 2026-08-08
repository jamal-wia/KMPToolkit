package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageConfigTest {

    @Test
    fun `the default config names no store so the platform derives one`() {
        assertNull(StorageConfig().name)
    }

    @Test
    fun `a supplied name is kept verbatim`() {
        assertEquals("com.example.session", StorageConfig("com.example.session").name)
    }

    @Test
    fun `a blank name is rejected`() {
        assertFailsWith<IllegalArgumentException> { StorageConfig("   ") }
    }

    @Test
    fun `an empty name is rejected`() {
        assertFailsWith<IllegalArgumentException> { StorageConfig("") }
    }

    @Test
    fun `a name containing a forward slash is rejected`() {
        // A SharedPreferences file name containing a separator writes outside the preferences
        // directory instead of failing.
        assertFailsWith<IllegalArgumentException> { StorageConfig("com.example/session") }
    }

    @Test
    fun `a name containing a backslash is rejected`() {
        assertFailsWith<IllegalArgumentException> { StorageConfig("com.example\\session") }
    }

    @Test
    fun `a name containing a space is rejected`() {
        assertFailsWith<IllegalArgumentException> { StorageConfig("com example") }
    }

    @Test
    fun `the plain and the secure store of one name are different stores`() {
        assertNotEquals(plainStoreId("app"), secureStoreId("app"))
    }

    @Test
    fun `the secure store and its key alias are different identifiers`() {
        assertNotEquals(secureStoreId("app"), secureKeyAlias("app"))
    }

    @Test
    fun `two names never derive the same identifier`() {
        val first: Set<String> = setOf(plainStoreId("a"), secureStoreId("a"), secureKeyAlias("a"))
        val second: Set<String> = setOf(plainStoreId("b"), secureStoreId("b"), secureKeyAlias("b"))

        assertEquals(emptySet(), first intersect second)
    }

    @Test
    fun `every derived identifier extends the name rather than replacing it`() {
        // A consumer debugging with adb or a Keychain dump has to be able to find their own store
        // by the name they passed.
        val name = "com.example.app"

        assertTrue(plainStoreId(name).startsWith(name))
        assertTrue(secureStoreId(name).startsWith(name))
        assertTrue(secureKeyAlias(name).startsWith(name))
    }

    @Test
    fun `a name that is a prefix of another does not derive a colliding identifier`() {
        // "app" and "app.extra" must not both resolve onto "app.extra.kmptoolkit.storage".
        assertNotEquals(plainStoreId("app"), plainStoreId("app.extra"))
        assertNotEquals(secureStoreId("app"), secureStoreId("app.extra"))
    }

    @Test
    fun `the derived plain store identifier is never the bare name`() {
        // On iOS an NSUserDefaults suite named exactly the bundle id resolves to the standard
        // defaults, where clear() would wipe everything the app ever wrote. The suffix is what
        // makes that unreachable even when a consumer passes their bundle id explicitly.
        assertNotEquals("com.example.app", plainStoreId("com.example.app"))
    }
}
