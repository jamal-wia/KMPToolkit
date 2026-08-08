package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecItemCopyMatching
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The regression guard for the iOS 26 `errSecParam` failure.
 *
 * A dictionary bridged from a Kotlin `Map` or an `NSMutableDictionary` is rejected there with
 * `-50`, and because the rejection arrives as a status on a call nobody expects to fail, it shows
 * up in an app as a hang or an empty Keychain rather than as an error. Asserting on the *status*
 * of a lookup for an item that does not exist pins the distinction: `errSecItemNotFound` means the
 * query was understood, `errSecParam` means it was not.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainQueryTest {

    @Test
    fun `a query built this way is accepted by the Security framework`() {
        val status: Int = KeychainQuery()
            .apply {
                putConstant(kSecClass, kSecClassGenericPassword)
                putString(kSecAttrService, "io.github.jamal_wia.kmptoolkit.storage.querytest")
                putString(kSecAttrAccount, "definitely-not-written")
                putConstant(kSecReturnData, kCFBooleanTrue)
                putConstant(kSecMatchLimit, kSecMatchLimitOne)
            }
            .use { SecItemCopyMatching(it, null) }

        // Not an equality assertion on errSecItemNotFound: a test executable has no Keychain at
        // all and answers errSecNotAvailable. What distinguishes "the query was understood" from
        // "the query was rejected" is exactly the absence of errSecParam.
        assertNotEquals(ERR_SEC_PARAM, status)
        assertTrue(status == errSecItemNotFound || status == ERR_SEC_NOT_AVAILABLE, "status $status")
    }

    @Test
    fun `a query with many entries is still accepted`() {
        // The failure mode being guarded against does not depend on the number of entries, but a
        // dictionary that outgrows its initial capacity is a different code path in CoreFoundation.
        val status: Int = KeychainQuery()
            .apply {
                putConstant(kSecClass, kSecClassGenericPassword)
                repeat(times = 20) { index -> putString(kSecAttrAccount, "account-$index") }
                putString(kSecAttrService, "io.github.jamal_wia.kmptoolkit.storage.querytest")
                putConstant(kSecMatchLimit, kSecMatchLimitOne)
            }
            .use { SecItemCopyMatching(it, null) }

        assertNotEquals(ERR_SEC_PARAM, status)
    }

}
