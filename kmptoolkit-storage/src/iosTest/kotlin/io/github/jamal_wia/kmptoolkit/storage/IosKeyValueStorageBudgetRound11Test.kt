package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * B round 11, Q4: the iOS twins of the cheap round 10 budget and secrets tests, for the plain store over `NSUserDefaults`
 * (the Keychain store has no round trip on a simulator test executable: no entitlement, every call is errSecNotAvailable,
 * see IosSecureKeyValueStorageTest). Contract: docs/kmptoolkit-storage/04-api-reference ("any key and any value reads back
 * as written", "clear removes everything this store put in") and 05-platform-notes (the iOS store is a suite of its own).
 * Each store is cleared before and after.
 */
class IosKeyValueStorageBudgetRound11Test {

    private fun store(name: String): KeyValueStorage = createKeyValueStorage(StorageConfig(name))

    @Test
    fun `five thousand entries read back from a second instance and one clear removes them all`() = runTest(timeout = 120.seconds) {
        val first: KeyValueStorage = store("round11budget")
        first.clear()
        try {
            repeat(5_000) { index: Int ->
                assertTrue(first.put("key-$index", "value-$index-${"x".repeat(index % 50)}") is StorageResult.Success, "put #$index")
            }
            val second: KeyValueStorage = store("round11budget")
            repeat(5_000) { index: Int ->
                assertEquals("value-$index-${"x".repeat(index % 50)}", second.get("key-$index").getOrNull(), "entry #$index")
            }

            assertTrue(second.clear() is StorageResult.Success)

            repeat(5_000) { index: Int -> assertNull(first.get("key-$index").getOrNull(), "entry #$index survived clear") }
        } finally {
            first.clear()
        }
    }

    @Test
    fun `names the system or a framework may handle specially read back as written in a suite of its own`() = runTest {
        val storage: KeyValueStorage = store("round11systemkeys")
        storage.clear()
        try {
            val keys: List<String> = listOf(
                "AppleLocale", "AppleKeyboards", "NSLanguages", "AppleITunesStoreItemKinds", "NSNavLastRootDirectory",
                "AppleTemperatureUnit", "AppleMeasurementUnits", "AppleFirstWeekday", "NSUserDefaultsRegistrationDomain",
                "com.apple.PreferenceSync", "ApplePersistence", "NSQuitAlwaysKeepsWindows", "AppleInterfaceStyle",
            )
            keys.forEachIndexed { index: Int, key: String ->
                val result: StorageResult<Unit> = storage.put(key, "v$index")
                if (result is StorageResult.Success) {
                    assertEquals("v$index", storage.get(key).getOrNull(), "a Success put of '$key' did not read back")
                } else {
                    assertTrue(result.errorOrNull() is StorageError.OperationFailed, "'$key' failed as ${result.errorOrNull()}")
                }
            }
        } finally {
            storage.clear()
        }
    }

    @Test
    fun `sixteen writers over fifty instances of one suite only ever leave one whole written value`() = runTest(timeout = 120.seconds) {
        val name = "round11race"
        store(name).clear()
        try {
            val candidates: List<String> = List(16) { writer: Int -> "writer-$writer-${"z".repeat(1_000 + writer)}" }
            withContext(Dispatchers.Default) {
                (0 until 16).map { writer: Int ->
                    async {
                        repeat(50) { round: Int ->
                            val storage: KeyValueStorage = store(name)
                            assertTrue(storage.put("shared", candidates[writer]) is StorageResult.Success, "put by $writer in round $round")
                            val seen: String? = storage.get("shared").getOrNull()
                            assertTrue(seen in candidates, "a reader saw a value nobody wrote: ${seen?.take(40)}")
                        }
                    }
                }.awaitAll()
            }
            assertTrue(store(name).get("shared").getOrNull() in candidates)
        } finally {
            store(name).clear()
        }
    }

    @Test
    fun `a key of three hundred characters and a value ending in a line break read back exactly`() = runTest {
        val storage: KeyValueStorage = store("round11edges")
        storage.clear()
        try {
            val key: String = "k".repeat(300)
            for (value: String in listOf("tail\n", "tail\r\n", "tail\n\uE000", "\n\n")) {
                assertTrue(storage.put(key, value) is StorageResult.Success)
                assertEquals(value, storage.get(key).getOrNull(), "value '${value.replace("\n", "\\n").replace("\r", "\\r")}' changed")
            }
        } finally {
            storage.clear()
        }
    }
}
