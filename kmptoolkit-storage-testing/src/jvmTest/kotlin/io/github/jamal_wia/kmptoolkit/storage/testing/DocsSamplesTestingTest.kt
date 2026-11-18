package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Plan 6.7 (a) for docs/kmptoolkit-storage/06-testing.md: the fixture snippets of the document, compiled in a consumer module (this one
 * depends on kmptoolkit-storage through `api(project(...))`, an ordinary project dependency) and run. The text between the `sample:begin`
 * and `sample:end` lines is the document's, kept equal by `DocsSamplesDriftTest` in kmptoolkit-storage's jvmTest. `Session` and
 * `SessionLoader` are the "code around a store" the document tests: an app's own code, defined once here.
 */
class DocsSamplesTestingTest {

    private sealed interface Session {
        data class Resumed(val token: String) : Session
        data object SignedOut : Session
        data object RetryLater : Session
        data object Unsupported : Session
    }

    private class SessionLoader(private val storage: EncryptedKeyValueStorage) {
        suspend fun load(): Session = when (val read: StorageResult<String?> = storage.get("refresh_token")) {
            is StorageResult.Success -> read.value?.let { Session.Resumed(it) } ?: Session.SignedOut
            is StorageResult.Failure -> when (read.error) {
                is StorageError.Unavailable -> Session.RetryLater
                is StorageError.Unsupported -> Session.Unsupported
                else -> Session.SignedOut
            }
        }
    }

    // sample:begin testing-resumed
    @Test
    fun `a stored session is resumed`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.put("refresh_token", "abc")

        val session = SessionLoader(storage).load()

        assertEquals(Session.Resumed("abc"), session)
    }
    // sample:end testing-resumed

    // sample:begin testing-restart
    @Test
    fun `the session survives a process restart`() = runTest {
        val backing = InMemoryKeyValueStorage.Backing()
        InMemoryKeyValueStorage(backing = backing).put("refresh_token", "abc")

        // "Restart": a new instance over the same backing. Knobs and write logs do not follow it.
        val restarted = InMemoryKeyValueStorage(backing = backing)

        assertEquals(Session.Resumed("abc"), SessionLoader(restarted).load())
    }

    @Test
    fun `a store that is unsupported here is reported as unsupported, not as signed out`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.failEveryOperationWith = StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)

        assertEquals(Session.Unsupported, SessionLoader(storage).load())
    }
    // sample:end testing-restart

    // sample:begin testing-error-paths
    @Test
    fun `a destroyed key forces a fresh sign-in`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.put("refresh_token", "abc")
        storage.failNextOperationWith = StorageError.Undecryptable("refresh_token")

        assertEquals(Session.SignedOut, SessionLoader(storage).load())
    }

    @Test
    fun `a Keychain that is not ready is retried rather than treated as signed out`() = runTest {
        val storage = InMemoryKeyValueStorage()
        storage.failNextOperationWith = StorageError.Unavailable()

        assertEquals(Session.RetryLater, SessionLoader(storage).load())
    }
    // sample:end testing-error-paths
}
