package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 7, the plain iOS store against the behaviours Android and desktop already promise: names that
 * differ only in case are separate stores, any key and value reads back as written, and a large value
 * survives. Each store is cleared at the end.
 */
class IosKeyValueStorageRound7Test {

    private fun store(name: String): KeyValueStorage = createKeyValueStorage(StorageConfig(name))

    @Test
    fun `store names that differ only in case are separate stores`() = runTest {
        val upper: KeyValueStorage = store("Round7CaseStore")
        val lower: KeyValueStorage = store("round7casestore")
        try {
            assertTrue(upper.put("k", "upper") is StorageResult.Success)
            assertTrue(lower.put("k", "lower") is StorageResult.Success)
            assertTrue(upper.put("only-upper", "u") is StorageResult.Success)

            assertEquals("upper", upper.get("k").getOrNull())
            assertEquals("lower", lower.get("k").getOrNull())
            assertEquals(null, lower.get("only-upper").getOrNull())
            assertTrue(lower.clear() is StorageResult.Success)
            assertEquals("upper", upper.get("k").getOrNull(), "clearing one name wiped the other")
        } finally {
            upper.clear()
            lower.clear()
        }
    }

    @Test
    fun `awkward keys read back as written and do not touch each other`() = runTest {
        val storage: KeyValueStorage = store("round7keys")
        try {
            val keys: List<String> = listOf(
                "", ".", "..", "a.b", "a.b.c", "NSGlobalDomain", "com.apple.x", "\u0000", "a\u0000b",
                "\n", "k\r\n", "😀", "é", "é", "@", "\$", "k".repeat(10_000),
            )
            keys.forEachIndexed { index: Int, key: String ->
                assertTrue(storage.put(key, "v$index") is StorageResult.Success, "put of key #$index")
            }
            keys.forEachIndexed { index: Int, key: String ->
                assertEquals("v$index", storage.get(key).getOrNull(), "key #$index (${key.length} chars) lost its value")
            }
            keys.forEachIndexed { index: Int, key: String ->
                assertTrue(storage.remove(key) is StorageResult.Success)
                assertEquals(null, storage.get(key).getOrNull(), "key #$index survived remove")
            }
        } finally {
            storage.clear()
        }
    }

    @Test
    fun `awkward values read back exactly`() = runTest {
        val storage: KeyValueStorage = store("round7values")
        try {
            val values: List<String> = listOf(
                "", " ", "\n", "\r\n", "\u0000", "a\u0000b", "\u0001", "￾", "￿", "😀", "<![CDATA[x]]>", "&amp;", "\\",
                "é", "é", "x".repeat(2_000_000),
            )
            values.forEachIndexed { index: Int, value: String ->
                assertTrue(storage.put("k$index", value) is StorageResult.Success, "put #$index")
            }
            values.forEachIndexed { index: Int, value: String ->
                val read: String? = storage.get("k$index").getOrNull()
                assertTrue(read == value, "value #$index (${value.length} chars) came back as ${read?.length} chars")
            }
        } finally {
            storage.clear()
        }
    }
}
