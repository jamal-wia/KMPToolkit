@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSMutableArray
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * The parse of the `NSLocationTemporaryUsageDescriptionDictionary` value of `Info.plist`
 * ([temporaryUsagePurposeKeys]) on real Foundation objects, the type the bundle really returns. The fake port
 * cannot reveal a wrong cast here: a parse that reads a correctly configured dictionary as empty would make
 * `createPermissionController` throw at startup for every consumer of the feature (docs/kmptoolkit-permission/03-guide.md).
 */
class TemporaryUsagePurposeKeysTest {

    private fun dictionary(vararg entries: Pair<String, String>): NSMutableDictionary =
        NSMutableDictionary().also { dictionary: NSMutableDictionary ->
            entries.forEach { (key: String, text: String) -> dictionary.setObject(text, forKey = NSString.create(string = key)) }
        }

    @Test
    fun `an NSMutableDictionary with two string keys yields both`() {
        val value: NSMutableDictionary = dictionary("NavigationKey" to "Turn-by-turn", "FindKey" to "Find a store")

        assertEquals(setOf("NavigationKey", "FindKey"), temporaryUsagePurposeKeys(value))
    }

    @Test
    fun `an empty NSMutableDictionary yields no key`() {
        assertTrue(temporaryUsagePurposeKeys(NSMutableDictionary()).isEmpty())
    }

    @Test
    fun `a non-string key is ignored and the string keys stay`() {
        val value: NSMutableDictionary = dictionary("NavigationKey" to "Turn-by-turn")
        value.setObject("Numbered", forKey = NSNumber(int = 7))

        assertEquals(setOf("NavigationKey"), temporaryUsagePurposeKeys(value))
    }

    @Test
    fun `an NSArray or an NSString value yields no key`() {
        val array: NSMutableArray = NSMutableArray().also { it.addObject("NavigationKey") }
        val string: NSString = NSString.create(string = "NavigationKey")

        assertTrue(temporaryUsagePurposeKeys(array).isEmpty(), "array")
        assertTrue(temporaryUsagePurposeKeys(string).isEmpty(), "string")
        assertTrue(temporaryUsagePurposeKeys("NavigationKey").isEmpty(), "Kotlin string")
        assertTrue(temporaryUsagePurposeKeys(NSNumber(int = 1)).isEmpty(), "number")
    }

    @Test
    fun `null yields no key`() {
        assertTrue(temporaryUsagePurposeKeys(null).isEmpty())
    }

    @Test
    fun `a Kotlin map yields its string keys`() {
        assertEquals(setOf("NavigationKey", "FindKey"), temporaryUsagePurposeKeys(mapOf("NavigationKey" to "a", "FindKey" to "b")))
    }

    @Test
    fun `the bundle read agrees with the parse of the same value`() {
        // The test bundle carries no such entry, so the read must be what the parse gives for an absent value;
        // the positive path is the cases above.
        assertEquals(temporaryUsagePurposeKeys(null), temporaryUsagePurposeKeysInInfoPlist())
    }
}
