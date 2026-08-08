package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The plain store, held to the shared contract plus what is specific to `SharedPreferences`. */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyValueStorageTest : KeyValueStorageContractTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    override fun create(config: StorageConfig): KeyValueStorage =
        createKeyValueStorage(context, config)

    @Test
    fun `values are stored in the clear because this store promises no encryption`() {
        // Asserted rather than assumed: a consumer choosing between the two stores is choosing on
        // exactly this, and a plain store that happened to encrypt would make the secure one look
        // pointless while giving none of its guarantees.
        val storage: KeyValueStorage = create()

        storage.put("k", "plain-value")

        assertEquals("plain-value", rawPreferenceValue(plainStoreId(NAME), "k"))
    }

    @Test
    fun `the default config derives the store name from the application package`() {
        val storage: KeyValueStorage = createKeyValueStorage(context)

        storage.put("k", "v")

        assertEquals(
            "v",
            rawPreferenceValue(plainStoreId(context.packageName), "k"),
        )
    }

    @Test
    fun `the default store is not the same file as an explicitly named one`() {
        createKeyValueStorage(context).put("k", "default")
        createKeyValueStorage(context, StorageConfig("other.name")).put("k", "explicit")

        assertEquals("default", createKeyValueStorage(context).get("k").getOrNull())
    }

    @Test
    fun `a value of a non-String type left in the file by other code is reported not returned`() {
        // SharedPreferences is shared with anything else the app points at the same file. A
        // ClassCastException from getString must not escape into a caller.
        context.getSharedPreferences(plainStoreId(NAME), Context.MODE_PRIVATE)
            .edit()
            .putInt("k", 42)
            .commit()

        val result: StorageResult<String?> = create().get("k")

        assertEquals(
            StorageError.OperationFailed(StorageOperation.GET, key = "k"),
            (result.errorOrNull() as StorageError.OperationFailed).copy(cause = null),
        )
    }

    @Test
    fun `the store passed to a DeviceIdProvider yields a stable id across instances`() {
        val first: String = DeviceIdProvider(create()).current()

        assertEquals(first, DeviceIdProvider(create()).current())
    }

    @Test
    fun `a device id is regenerated after the store is cleared`() {
        val storage: KeyValueStorage = create()
        val first: String = DeviceIdProvider(storage).current()

        assertTrue(storage.clear().isSuccess)

        assertNotEquals(first, DeviceIdProvider(storage).current())
    }

    private fun rawPreferenceValue(fileName: String, key: String): String? =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).getString(key, null)
}
