package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The forms in which the platform says "the device is locked" ([isDeviceLockedFailure]) and the API 31-32 shape
 * ([isKeystore2LockedShape]), level by level. The levels are the `sdkInt` parameters, so one Robolectric level (35, whose
 * framework has `KeyStoreException.requiresUserAuthentication()`) can answer for all of them.
 *
 * Contract: the KDoc of [isDeviceLockedFailure], 05-platform-notes "Key options" (locked device) and the options KDoc of
 * [AndroidSecureStorageOptions.unlockedDeviceRequired]. Codes are KeyMint's: -72 DEVICE_LOCKED, -26
 * KEY_USER_NOT_AUTHENTICATED, -4 (a system error that is none of the two).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidLockedDeviceFailureTest {

    private fun keystore2Refusal(code: Int): InvalidKeyException =
        InvalidKeyException(KEYSTORE2_OPERATION_FAILED_MESSAGE, platformKeyStoreException(code, "code $code"))

    @Test
    fun `control - the keystore 1 form is a locked device at every level`() {
        for (sdk: Int in listOf(28, 30, 31, 32, 33, 35)) {
            assertTrue(isDeviceLockedFailure(UserNotAuthenticatedException(), sdk), "sdk=$sdk")
            assertTrue(isDeviceLockedFailure(IllegalStateException("x", UserNotAuthenticatedException()), sdk), "sdk=$sdk")
        }
    }

    @Test
    fun `from API 33 a KeyStoreException that requires user authentication is a locked device, whatever wraps it`() {
        for (code: Int in listOf(-72, -26)) {
            assertTrue(isDeviceLockedFailure(keystore2Refusal(code), sdkInt = 33), "code=$code")
            assertTrue(isDeviceLockedFailure(ProviderException("p", keystore2Refusal(code)), sdkInt = 35), "code=$code")
        }
    }

    @Test
    fun `from API 33 a KeyStoreException that does not require user authentication is not a locked device`() {
        for (code: Int in listOf(-4, -68, 4)) {
            assertFalse(isDeviceLockedFailure(keystore2Refusal(code), sdkInt = 33), "code=$code answers requiresUserAuthentication=false")
        }
        assertFalse(isDeviceLockedFailure(InvalidKeyException("Keystore operation failed"), sdkInt = 35))
    }

    @Test
    fun `below API 33 the platform question is not asked, so the plain refusal is not a locked device by itself`() {
        for (sdk: Int in listOf(28, 30, 31, 32)) {
            assertFalse(isDeviceLockedFailure(keystore2Refusal(-72), sdk), "sdk=$sdk: only the cipher's marker can tell")
        }
    }

    @Test
    fun `a DeviceLockedKeyException is a locked device at every level and keeps the platform refusal as its cause`() {
        val original: InvalidKeyException = keystore2Refusal(-72)
        val marked = DeviceLockedKeyException(original)

        assertEquals(original, marked.cause)
        assertEquals(KEYSTORE2_OPERATION_FAILED_MESSAGE, marked.message)
        for (sdk: Int in listOf(28, 31, 32, 33, 35)) {
            assertTrue(isDeviceLockedFailure(marked, sdk), "sdk=$sdk")
            assertTrue(isDeviceLockedFailure(IllegalStateException("w", marked), sdk), "sdk=$sdk")
        }
    }

    @Test
    fun `the API 31-32 shape is exactly an InvalidKeyException with the provider's message and a KeyStoreException cause`() {
        val shape: InvalidKeyException = keystore2Refusal(-72)

        assertTrue(isKeystore2LockedShape(shape, sdkInt = 31))
        assertTrue(isKeystore2LockedShape(shape, sdkInt = 32))
    }

    @Test
    fun `the API 31-32 shape is false at every other level`() {
        for (sdk: Int in listOf(24, 28, 30, 33, 34, 35)) {
            assertFalse(isKeystore2LockedShape(keystore2Refusal(-72), sdk), "sdk=$sdk")
        }
    }

    @Test
    fun `the API 31-32 shape is false for the platform's named subclasses, another message and another cause`() {
        val keystoreCause: Exception = platformKeyStoreException(-72, "device is locked.")
        val notShapes: Map<String, Throwable> = mapOf(
            "KeyPermanentlyInvalidatedException" to KeyPermanentlyInvalidatedException(KEYSTORE2_OPERATION_FAILED_MESSAGE, keystoreCause),
            "UserNotAuthenticatedException" to UserNotAuthenticatedException(KEYSTORE2_OPERATION_FAILED_MESSAGE, keystoreCause),
            "another message" to InvalidKeyException("Failed to obtain information about key", keystoreCause),
            "no message" to InvalidKeyException(null as String?, keystoreCause),
            "no cause" to InvalidKeyException(KEYSTORE2_OPERATION_FAILED_MESSAGE),
            "a ProviderException cause" to InvalidKeyException(KEYSTORE2_OPERATION_FAILED_MESSAGE, ProviderException("p")),
            "java.security.KeyStoreException cause" to
                InvalidKeyException(KEYSTORE2_OPERATION_FAILED_MESSAGE, java.security.KeyStoreException("k")),
            "not an InvalidKeyException" to ProviderException(KEYSTORE2_OPERATION_FAILED_MESSAGE, keystoreCause),
            "the shape one level deeper" to InvalidKeyException("outer", keystore2Refusal(-72)),
        )
        for ((label: String, error: Throwable) in notShapes) {
            for (sdk: Int in listOf(31, 32)) {
                assertFalse(isKeystore2LockedShape(error, sdk), "$label at sdk=$sdk")
            }
        }
    }
}
