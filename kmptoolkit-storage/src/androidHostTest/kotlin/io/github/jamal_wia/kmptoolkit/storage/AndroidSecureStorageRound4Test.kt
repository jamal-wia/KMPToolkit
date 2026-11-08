package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The encrypted Android store with odd values, values another component wrote into its file, a
 * restart (a preferences object that loads the file afresh), and a plain store of the same name.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecureStorageRound4Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun uniqueName(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    private fun coldPreferences(fileName: String): SharedPreferences {
        val file = File(File(context.dataDir, "shared_prefs"), "$fileName.xml")
        val type: Class<*> = Class.forName("android.app.SharedPreferencesImpl")
        val constructor = type.getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, Context.MODE_PRIVATE) as SharedPreferences
    }

    private fun secure(name: String, preferences: SharedPreferences): SecureKeyValueStorage =
        AndroidSecureKeyValueStorage(
            preferences = preferences,
            cipher = KeystoreValueCipher(TestKeys.source(name)),
            dispatcher = Dispatchers.IO,
        )

    private fun describe(text: String): String =
        if (text.length > 20) "len=${text.length}" else text.map { "%04x".format(it.code) }.joinToString(" ")

    @Test
    fun `odd values survive a restart of the preferences file and decrypt under the same key`() {
        val name: String = uniqueName("secure_odd")
        val fileName: String = secureStoreId(name)
        val values: List<String> = listOf(
            "", " ", "\n", "\r\n", "\u0000", "a\u0000b", "\u0001", "￾", "￿", "😀",
            "<![CDATA[x]]>", "&amp;", "\\", "x".repeat(3_000_000),
        )
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                val writer: SecureKeyValueStorage = secure(name, context.getSharedPreferences(fileName, Context.MODE_PRIVATE))
                values.forEachIndexed { index: Int, value: String ->
                    if (writer.put("k$index", value) !is StorageResult.Success) problems += "put failed for ${describe(value)}"
                }
                val restarted: SecureKeyValueStorage = secure(name, coldPreferences(fileName))
                values.forEachIndexed { index: Int, value: String ->
                    val read: StorageResult<String?> = restarted.get("k$index")
                    if (read.getOrNull() != value) problems += "after restart ${describe(value)} gave $read".take(200)
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `entries another component wrote into the file read as Undecryptable and never throw`() {
        val name: String = uniqueName("secure_foreign")
        val fileName: String = secureStoreId(name)
        val preferences: SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        val twelveBytes: String = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
        val thirteenBytes: String = Base64.encodeToString(ByteArray(13), Base64.NO_WRAP)
        val foreign: Map<String, String> = mapOf(
            "empty" to "", "blank" to "   ", "plain" to "just text", "pad" to "AAAA=", "bang" to "!!!!",
            "twelve" to twelveBytes, "thirteen" to thirteenBytes, "long" to "A".repeat(100_000), "nl" to "AA\nAA",
        )
        val edit = preferences.edit()
        foreign.forEach { (key: String, value: String) -> edit.putString(key, value) }
        edit.putInt("int", 5).putBoolean("bool", true).putStringSet("set", setOf("a"))
        assertTrue(edit.commit())
        val storage: SecureKeyValueStorage = secure(name, preferences)
        runBlocking {
            withTimeout(30_000) {
                for (key in foreign.keys + listOf("int", "bool", "set")) {
                    val read: StorageResult<String?> = storage.get(key)
                    assertTrue(
                        read is StorageResult.Failure && (read.error is StorageError.Undecryptable || read.error is StorageError.OperationFailed),
                        "$key read as $read",
                    )
                }
                // The store still works, and the foreign entries are replaced by a normal put.
                assertTrue(storage.put("plain", "now encrypted") is StorageResult.Success)
                assertEquals("now encrypted", storage.get("plain").getOrNull())
                assertTrue(storage.clear() is StorageResult.Success)
                assertEquals(null, storage.get("int").getOrNull())
            }
        }
    }

    @Test
    fun `clearing the plain store of one name leaves the encrypted store of that name untouched, and the other way round`() {
        val name: String = uniqueName("both")
        val plain: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        val secure: SecureKeyValueStorage =
            secure(name, context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE))
        runBlocking {
            withTimeout(30_000) {
                plain.put("k", "plain")
                secure.put("k", "secret")
                assertTrue(plain.clear() is StorageResult.Success)
                assertEquals("secret", secure.get("k").getOrNull())
                plain.put("k", "plain again")
                assertTrue(secure.clear() is StorageResult.Success)
                assertEquals("plain again", plain.get("k").getOrNull())
            }
        }
    }

    @Test
    fun `a device id over an undecryptable entry is replaced once and then stable`() {
        val name: String = uniqueName("secure_devid")
        val preferences: SharedPreferences =
            context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)
        preferences.edit().putString(DeviceIdProvider.DEFAULT_KEY, "garbage that is not ciphertext").commit()
        val storage: SecureKeyValueStorage = secure(name, preferences)
        val provider = DeviceIdProvider(storage)
        runBlocking {
            withTimeout(30_000) {
                val first: String = provider.current()
                assertEquals(first, provider.current(), "the id was not persisted over the undecryptable entry")
                assertEquals(first, storage.get(DeviceIdProvider.DEFAULT_KEY).getOrNull())
                assertEquals(first, DeviceIdProvider(secure(name, coldPreferences(secureStoreId(name)))).current())
            }
        }
    }
}
