package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Round 7 SUSPECT: keys the iOS plain store accepts with Success but cannot read back. Not committed. */
class IosKeyValueStorageRound7KeysTest {

    @Test
    fun `every key that put accepts reads back as written`() = runTest {
        val storage: KeyValueStorage = createKeyValueStorage(StorageConfig("round7keysuspect"))
        val problems: MutableList<String> = mutableListOf()
        try {
            val keys: List<String> = listOf(
                "", ".", "..", "a.b", "a.b.c", "AppleLanguages", "AppleLocale", "NSGlobalDomain", "NSLanguages",
                "AppleKeyboards", "AppleInterfaceStyle", "com.apple.x", "\u0000", "a\u0000b",
                "\n", "k\r\n", "😀", "é", "é", "@", "\$", "k".repeat(10_000),
            )
            keys.forEachIndexed { index: Int, key: String ->
                val put: StorageResult<Unit> = storage.put(key, "v$index")
                val read: String? = storage.get(key).getOrNull()
                if (put is StorageResult.Success && read != "v$index") {
                    problems += "key #$index '${key.take(20).map { it.code }.joinToString(",")}' (${key.length} chars): put Success, get $read"
                }
            }
        } finally {
            storage.clear()
        }
        assertEquals(emptyList(), problems)
    }
}
