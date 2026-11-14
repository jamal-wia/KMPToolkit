package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * B round 9, Q4: the same scenario script over [InMemoryKeyValueStorage] and the real desktop store
 * (`createKeyValueStorage(directory, config)`), identical observable results required. Contract (docs 06-testing): absent
 * reads back as null, "" as "", put overwrites, remove of an absent key succeeds, clear empties the store, a put of an unpaired
 * surrogate is OperationFailed and changes nothing, and what a real store accepts the fake accepts.
 */
class InMemoryVersusDesktopStoreRound9Test {
    private val directory: File = Files.createTempDirectory("kmptoolkit-storage-testing-r9").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val values: List<Pair<String, String>> = listOf(
        "plain" to "value",
        "empty" to "",
        "spaces around" to "  padded  ",
        " outer spaces " to "kept",
        "equals=sign" to "a=b=c",
        "colon:key" to "x:y",
        "hash#key" to "#not a comment",
        "bang!key" to "!not a comment either",
        "back\\slash" to "C:\\dir\\file\\u0041",
        "line\nbreak" to "first\nsecond\r\nthird\rfourth",
        "trailing newline" to "pem\n",
        "trailing crlf" to "json\r\n",
        "unicode ключ" to "значение \u00e9\u0301 \uD83D\uDE00",
        "tab\tkey" to "\ttab value\t",
        "nul" to "a\u0000b",
        "U+E000" to "text\uE000",
        "long" to "x".repeat(200_000),
    )

    private suspend fun script(store: KeyValueStorage): List<String> {
        val log: MutableList<String> = mutableListOf()
        fun describe(result: StorageResult<*>): String = when (result) {
            is StorageResult.Success -> "ok:" + (result.value as? String)?.let { "${it.length}:${it.hashCode()}" }
            is StorageResult.Failure -> "fail:" + (result.error as? StorageError.OperationFailed)?.let { "${it.operation}/${it.key}" }
        }
        for ((key: String, value: String) in values) {
            log += "put $key " + describe(store.put(key, value))
            log += "get $key " + describe(store.get(key))
        }
        log += "get absent " + describe(store.get("absent"))
        log += "overwrite " + describe(store.put("plain", "second"))
        log += "get plain " + describe(store.get("plain"))
        log += "remove absent " + describe(store.remove("never-there"))
        log += "remove plain " + describe(store.remove("plain"))
        log += "get plain " + describe(store.get("plain"))
        for (bad: Pair<String, String> in listOf("k\uD800" to "v", "k" to "\uDC00v", "\uD83D" to "\uDE00")) {
            log += "put unpaired " + describe(store.put(bad.first, bad.second))
            log += "get after " + describe(store.get("k"))
        }
        for ((key: String, _: String) in values) log += "again $key " + describe(store.get(key))
        for ((key: String, _: String) in values.take(8)) {
            log += "remove $key " + describe(store.remove(key))
            log += "get removed $key " + describe(store.get(key))
        }
        for ((key: String, value: String) in values) log += "reput $key " + describe(store.put(key, value))
        log += "clear " + describe(store.clear())
        for ((key: String, _: String) in values) log += "after clear $key " + describe(store.get(key))
        log += "clear again " + describe(store.clear())
        log += "put after clear " + describe(store.put("fresh", "1"))
        log += "get fresh " + describe(store.get("fresh"))
        return log
    }

    @Test
    fun `the in memory store and the desktop store give identical results for one scenario script`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val real: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name = "r9"))
            val fake: List<String> = script(InMemoryKeyValueStorage())
            val actual: List<String> = script(real)
            assertEquals(fake.size, actual.size)
            val differences: List<String> = fake.indices.filter { fake[it] != actual[it] }.map { "memory='${fake[it]}' desktop='${actual[it]}'" }
            assertEquals(emptyList(), differences)
        }
    }

    @Test
    fun `an armed failure fails every operation kind with its own error and no effect then clears`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val store = InMemoryKeyValueStorage()
            store.put("k", "v")
            val error: StorageError = StorageError.OperationFailed(StorageOperation.GET, key = "k")
            for (call in listOf<suspend () -> StorageResult<*>>({ store.get("k") }, { store.put("k", "w") }, { store.remove("k") }, { store.clear() })) {
                store.failNextOperationWith = error
                assertIs<StorageResult.Failure>(call())
                assertEquals(mapOf("k" to "v"), store.contents)
            }
            assertEquals(StorageResult.Success("v"), store.get("k"))
        }
    }
}
