package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Breaker round 3 of group sto-ios (ticket 5.1.0, STO-5): every operation of the iOS secure store, called by a
 * coroutine that is already cancelled, for a plain store, a store with a configured group and a user-presence store.
 *
 * Contract: the operations are cancellable suspend functions (KDoc of `IosSecureKeyValueStorage`: "is cancellable";
 * docs/kmptoolkit-storage/05-platform-notes.md: cancelling a call dismisses its sheet and releases its `LAContext`).
 * A cancelled caller must get a `CancellationException`, never a result, must cause no Keychain call (a write or a
 * clear that runs anyway is a mutation the caller abandoned before it began), and must leave no authentication session
 * open. Round 1 checked only `ensureKey`; this covers get, put, remove, clear (both group paths) and ensureKey.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStoreCancellationBreakerR3Test {

    private val variants: List<Pair<String, IosSecureStorageOptions>> = listOf(
        "plain" to IosSecureStorageOptions(),
        "configured group" to IosSecureStorageOptions(accessGroup = "TEAM.shared"),
        "user presence" to IosSecureStorageOptions(
            userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Unlock"),
        ),
    )

    private val operations: List<Pair<String, suspend (IosSecureKeyValueStorage) -> StorageResult<*>>> = listOf(
        "get" to { store -> store.get("k") },
        "put (overwrite)" to { store -> store.put("k", "new") },
        "put (new key)" to { store -> store.put("fresh", "new") },
        "remove" to { store -> store.remove("k") },
        "clear" to { store -> store.clear() },
        "ensureKey" to { store -> store.ensureKey() },
    )

    @Test
    fun `a caller cancelled before the call gets CancellationException makes no Keychain call and leaves no session open`() =
        runTest {
            val problems: MutableList<String> = mutableListOf()
            for ((variant: String, options: IosSecureStorageOptions) in variants) {
                for ((name: String, operation: suspend (IosSecureKeyValueStorage) -> StorageResult<*>) in operations) {
                    val keychain = InMemoryKeychainPort()
                    keychain.seed(keychain.item("k", "old", group = options.accessGroup ?: keychain.defaultGroup))
                    val sessions = CountingAuthenticationSessions()
                    val store: IosSecureKeyValueStorage = storeOver(keychain, options, sessions)
                    var answered: StorageResult<*>? = null
                    var thrown: Throwable? = null

                    launch {
                        currentCoroutineContext().cancel()
                        try {
                            answered = operation(store)
                        } catch (caught: Throwable) {
                            thrown = caught
                        }
                    }.join()

                    val label = "$variant $name"
                    if (answered != null) problems += "$label answered $answered to a cancelled caller"
                    if (thrown !is CancellationException) problems += "$label threw $thrown instead of CancellationException"
                    if (keychain.calls.isNotEmpty()) problems += "$label made Keychain calls for a cancelled caller: ${keychain.calls}"
                    if (keychain.items.singleOrNull()?.account != "k") {
                        problems += "$label changed the Keychain: ${keychain.items.map { it.account }}"
                    }
                    if (sessions.opened != sessions.invalidated) {
                        problems += "$label leaked a session: opened=${sessions.opened} invalidated=${sessions.invalidated}"
                    }
                }
            }
            assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        }
}
