package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
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
 * A key or value with an unpaired surrogate cannot be written to an XML preferences file (it becomes
 * `?`, or the entry is lost), so the plain store answers OperationFailed and the file keeps what it had.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidStorageSurrogateRound4Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun coldPreferences(fileName: String): SharedPreferences {
        val file = File(File(context.dataDir, "shared_prefs"), "$fileName.xml")
        val type: Class<*> = Class.forName("android.app.SharedPreferencesImpl")
        val constructor = type.getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, Context.MODE_PRIVATE) as SharedPreferences
    }

    private fun assertRejectedAndOldValueKept(key: String, value: String) {
        val name = "sur" + UUID.randomUUID().toString().replace("-", "")
        val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
        runBlocking {
            withTimeout(20_000) {
                assertEquals(StorageResult.Success(Unit), storage.put("k", "old"))
                val error: StorageError? = storage.put(key, value).errorOrNull()
                assertTrue(
                    error is StorageError.OperationFailed && error.operation == StorageOperation.PUT,
                    "put of key/value with an unpaired surrogate answered $error",
                )
            }
        }
        val cold: SharedPreferences = coldPreferences(plainStoreId(name))
        assertEquals("old", cold.getString("k", null))
        assertEquals(1, cold.all.size, "the rejected put left an entry: ${cold.all.keys}")
    }

    @Test
    fun `a lone surrogate value is rejected and the stored value survives a cold reload`() {
        assertRejectedAndOldValueKept("k", "a\uD800b")
    }

    @Test
    fun `a lone surrogate key is rejected and the stored value survives a cold reload`() {
        assertRejectedAndOldValueKept("\uD800", "v")
    }
}
