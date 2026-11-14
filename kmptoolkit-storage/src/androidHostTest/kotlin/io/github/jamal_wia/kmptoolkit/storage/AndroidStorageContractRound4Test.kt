package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The plain Android store held to the contract's words with odd inputs: values and keys that are
 * legal Strings but awkward for an XML preferences file, read back both by the same preferences
 * object and by a second one that loads the file from disk (what the next process sees), and store
 * names at the edges of what [StorageConfig] accepts.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageContractRound4Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun uniqueName(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    /** A preferences object that has never seen this file: what a restarted process would build. */
    private fun coldPreferences(fileName: String): SharedPreferences {
        val file = File(File(context.dataDir, "shared_prefs"), "$fileName.xml")
        val type: Class<*> = Class.forName("android.app.SharedPreferencesImpl")
        val constructor = type.getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, Context.MODE_PRIVATE) as SharedPreferences
    }

    private fun oddValues(): List<String> = listOf(
        "", " ", "\t", "a\nb", "\n\nz", "\u0000", "a\u0000b", "\u0001", "\u001f", "\u007f", "\u0085", "\u2028",
        "\uFFFE", "\uFFFF", "\uD83D\uDE00", "<![CDATA[x]]>", "]]>", "&amp;",
        "\"q\" 'q' <b>", "  leading and trailing  ", "\\", "\\u0041", "=", ":", "#c", "x".repeat(1_000_000),
    )

    private fun oddKeys(): List<String> = listOf(
        "", " ", "\u0000", "a\u0001b", "key with spaces", "<>&\"'", "\uD83D\uDE00", "k".repeat(10_000),
        "\n", "=", "a/b", "a.b.c",
    )

    private fun describe(text: String): String =
        if (text.length > 20) "len=${text.length}" else text.map { "%04x".format(it.code) }.joinToString(" ")

    @Test
    fun `every legal value written with success reads back unchanged, from the same and from a cold preferences object`() {
        val name: String = uniqueName("odd_values")
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                oddValues().forEachIndexed { index: Int, value: String ->
                    val key = "k$index"
                    val put: StorageResult<Unit> = storage.put(key, value)
                    if (put !is StorageResult.Success) {
                        problems += "put failed for ${describe(value)}: $put"
                        return@forEachIndexed
                    }
                    val warm: String? = storage.get(key).getOrNull()
                    if (warm != value) problems += "warm read of ${describe(value)} gave ${warm?.let(::describe)}"
                }
            }
        }
        val cold: SharedPreferences = coldPreferences(plainStoreId(name))
        oddValues().forEachIndexed { index: Int, value: String ->
            val read: String? = cold.getString("k$index", null)
            if (read != value) problems += "cold read of ${describe(value)} gave ${read?.let(::describe)}"
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `every legal key written with success reads back, and the whole file survives a cold load`() {
        val name: String = uniqueName("odd_keys")
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                oddKeys().forEachIndexed { index: Int, key: String ->
                    val put: StorageResult<Unit> = storage.put(key, "v$index")
                    if (put !is StorageResult.Success) {
                        problems += "put failed for key ${describe(key)}: $put"
                        return@forEachIndexed
                    }
                    val warm: String? = storage.get(key).getOrNull()
                    if (warm != "v$index") problems += "warm read of key ${describe(key)} gave $warm"
                }
            }
        }
        val cold: SharedPreferences = coldPreferences(plainStoreId(name))
        oddKeys().forEachIndexed { index: Int, key: String ->
            val read: String? = cold.getString(key, null)
            if (read != "v$index") problems += "cold read of key ${describe(key)} gave $read"
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `one awkward value does not make the other entries unreadable after a cold load`() {
        val name: String = uniqueName("poison")
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        runBlocking {
            withTimeout(20_000) {
                storage.put("before", "kept")
                for (value in listOf("\u0000", "\u0001", "\uFFFE")) {
                    storage.put("awkward", value)
                    storage.put("after", "kept too")
                    val cold: SharedPreferences = coldPreferences(plainStoreId(name))
                    assertEquals("kept", cold.getString("before", null), "before lost after writing ${describe(value)}")
                    assertEquals("kept too", cold.getString("after", null), "after lost after writing ${describe(value)}")
                }
            }
        }
    }

    @Test
    fun `store names at the edge of what StorageConfig accepts never make an operation throw`() {
        val names: List<String> = listOf(
            ".", "..", "...", "a:b", "a\nb", "a\tb", "n".repeat(255), "n".repeat(300), "éè", "😀",
            "-", "~", "*", "?", "%", "a;b", "a|b", "a<b", "CON", "a\u0001b",
        ).filter { name: String ->
            // The store name becomes a file name: a Windows build machine cannot create `a:b` or `CON`
            // (java.io raises "Invalid file path" before the code under test sees anything), a device can.
            TestHost.isValidHostFileName(plainStoreId(name))
        }
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                for (name in names) {
                    try {
                        val config = StorageConfig(name)
                        val plain: KeyValueStorage = createKeyValueStorage(context, config)
                        val put: StorageResult<Unit> = plain.put("k", "v")
                        val get: StorageResult<String?> = plain.get("k")
                        if (put is StorageResult.Success && get.getOrNull() != "v") {
                            problems += "${describe(name)}: put succeeded, get gave $get"
                        }
                        plain.clear()
                    } catch (error: Throwable) {
                        problems += "${describe(name)}: threw $error"
                    }
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `clear of one store never touches a store whose name merely extends it`() {
        val base: String = uniqueName("scope")
        val narrow: KeyValueStorage = createKeyValueStorage(context, StorageConfig(base))
        val wide: KeyValueStorage = createKeyValueStorage(context, StorageConfig("$base.kmptoolkit"))
        val wider: KeyValueStorage = createKeyValueStorage(context, StorageConfig("$base.kmptoolkit.storage"))
        runBlocking {
            withTimeout(20_000) {
                narrow.put("k", "narrow")
                wide.put("k", "wide")
                wider.put("k", "wider")
                assertTrue(narrow.clear() is StorageResult.Success)
                assertEquals(null, narrow.get("k").getOrNull())
                assertEquals("wide", wide.get("k").getOrNull())
                assertEquals("wider", wider.get("k").getOrNull())
                assertTrue(wide.clear() is StorageResult.Success)
                assertEquals("wider", wider.get("k").getOrNull())
            }
        }
    }

    @Test
    fun `a key stored with another type is replaced by put and the device id then holds`() {
        val name: String = uniqueName("typed")
        val fileName: String = plainStoreId(name)
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit()
            .putInt(DeviceIdProvider.DEFAULT_KEY, 7).commit()
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        val provider = DeviceIdProvider(storage)
        runBlocking {
            withTimeout(20_000) {
                val first: String = provider.current()
                val second: String = provider.current()
                assertEquals(first, second, "the id was not persisted over the entry of another type")
                assertEquals(first, storage.get(DeviceIdProvider.DEFAULT_KEY).getOrNull())
            }
        }
    }
}
