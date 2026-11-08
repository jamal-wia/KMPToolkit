package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The framework's XML writer indents after a text that ends in a line break, so a value ending in
 * `\n` came back from the file with spaces appended after the process restarted. Every value written
 * with success must read back unchanged through a store that loads the file afresh.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageTrailingLineBreakRound4Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun coldPreferences(fileName: String): SharedPreferences {
        val file = File(File(context.dataDir, "shared_prefs"), "$fileName.xml")
        val type: Class<*> = Class.forName("android.app.SharedPreferencesImpl")
        val constructor = type.getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, Context.MODE_PRIVATE) as SharedPreferences
    }

    private fun newName(): String = "nl" + UUID.randomUUID().toString().replace("-", "")

    /** Writes through the store, then reads through a store over a preferences object that loads the file afresh. */
    private fun coldRoundTrip(value: String): String? {
        val name: String = newName()
        val writer: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        return runBlocking {
            withTimeout(20_000) {
                assertEquals(StorageResult.Success(Unit), writer.put("k", value))
                AndroidKeyValueStorage(coldPreferences(plainStoreId(name)), kotlinx.coroutines.Dispatchers.IO)
                    .get("k").getOrNull()
            }
        }
    }

    @Test
    fun `a line-break-only value survives a cold reload`() {
        assertEquals("\n", coldRoundTrip("\n"))
        assertEquals("\r\n", coldRoundTrip("\r\n"))
    }

    @Test
    fun `values ending in a line break round-trip across a cold reload`() {
        for (value: String in listOf("abc\n", "abc\n\n", "\n\n\n", "{\"a\":1}\n", "-----END-----\n", "x")) {
            assertEquals(value, coldRoundTrip(value), "value of length ${value.length}")
        }
    }

    @Test
    fun `values that look like the escape marker round-trip across a cold reload`() {
        for (value: String in listOf("abc\n", "\n", "", "abc\n    ", "a\n", "\n")) {
            assertEquals(value, coldRoundTrip(value), "value ${value.map { "%04x".format(it.code) }}")
        }
    }

    @Test
    fun `a raw entry that another component wrote is read back unchanged`() {
        val name: String = newName()
        val preferences: SharedPreferences = coldPreferences(plainStoreId(name))
        preferences.edit().putString("raw", "legacy").putString("old", "abc\n    ").commit()
        val reader: KeyValueStorage = AndroidKeyValueStorage(
            coldPreferences(plainStoreId(name)),
            kotlinx.coroutines.Dispatchers.IO,
        )
        runBlocking {
            withTimeout(20_000) {
                assertEquals("legacy", reader.get("raw").getOrNull())
                assertEquals("abc\n    ", reader.get("old").getOrNull())
            }
        }
    }
}
