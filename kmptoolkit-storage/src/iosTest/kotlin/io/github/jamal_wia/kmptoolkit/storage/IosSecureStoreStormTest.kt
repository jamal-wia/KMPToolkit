package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A fixed-seed storm over one store: many workers on a real multi-threaded dispatcher put, read, remove and clear
 * the same few keys through the model Keychain. The store holds no lock, so the invariants are the ones the contract
 * promises for racing writers: no operation fails (the `errSecDuplicateItem` retry absorbs every lost add), a read is
 * absent or a whole value some worker wrote (never torn), and after the storm a clear leaves nothing, probe included.
 *
 * Every failure message starts with `seed=` so a failing run can be replayed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStoreStormTest {

    @Test
    fun `racing puts and gets and removes and clears never fail and never tear a value`() = runTest(timeout = 180.seconds) {
        val seed = 1_000
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)
        val keys: List<String> = List(4) { "key-$it" }
        val problems: MutableList<String> = mutableListOf()

        val workers: List<List<String>> = List(WORKERS) { worker ->
            async(Dispatchers.Default) {
                val random = Random(seed * 1000 + worker)
                val local: MutableList<String> = mutableListOf()
                repeat(OPERATIONS) { step ->
                    val key: String = keys[random.nextInt(keys.size)]
                    val result: StorageResult<*> = when (random.nextInt(10)) {
                        in 0..4 -> store.put(key, "w$worker-$step")
                        in 5..7 -> store.get(key).also { read ->
                            val value: String? = (read as? StorageResult.Success)?.value
                            if (value != null && !VALUE.matches(value)) local += "seed=$seed torn value '$value' for $key"
                        }
                        8 -> store.remove(key)
                        // At most two workers clear, so at most two clears race for the default-group probe.
                        else -> if (worker < 2) store.clear() else store.remove(key)
                    }
                    if (result is StorageResult.Failure) local += "seed=$seed worker=$worker step=$step failed: ${result.error}"
                }
                local
            }
        }.awaitAll()
        workers.forEach { problems += it }

        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
        assertEquals(StorageResult.Success(Unit), store.clear(), "seed=$seed final clear")
        assertTrue(keychain.items.isEmpty(), "seed=$seed left over: ${keychain.items.map { it.service to it.account }}")
        for (key: String in keys) assertEquals(StorageResult.Success(null), store.get(key), "seed=$seed key=$key")
    }

    private companion object {
        const val WORKERS: Int = 8
        const val OPERATIONS: Int = 150
        val VALUE: Regex = Regex("w\\d+-\\d+")
    }
}
