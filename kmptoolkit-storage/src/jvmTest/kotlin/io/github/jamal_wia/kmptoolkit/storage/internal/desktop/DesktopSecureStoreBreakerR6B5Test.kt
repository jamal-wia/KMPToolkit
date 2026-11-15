package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 5 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-5), against task/sto-desktop cce622f0:
 * the round-7 fixes as they reach the encrypted stores.
 *
 * - B4-M1 / O12 / D-P11 through the STORE path (the round-4 tests covered a pipe at the store path and at the lock path):
 *   a store path that is a directory, a link to a directory, a link to a pipe, a link to a device, or a link whose target
 *   does not exist. Every call answers a typed result within its bound, nothing of the user's is moved, replaced or
 *   created through the link, no key item is created, no temporary file is left.
 * - The interrupted-caller paragraph of 05 as corrected in round 7, claim by claim, for every operation of both store
 *   types (and `changeKey`), on a dispatcher that runs the call on the interrupted thread.
 * - The undelivered-result wipe as it reaches the key cache: a key fetched for a caller that left is wiped and never
 *   cached.
 *
 * Fake binding only, POSIX file semantics for the link cases.
 */
class DesktopSecureStoreBreakerR6B5Test {

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    private fun SecureTestRig.quarantined(): List<String> = fileNames().filter { ".undecryptable-" in it }

    // ---- the store path is not a regular file ----

    private class Shape(val label: String, val make: (store: File, rig: SecureTestRig) -> Unit, val check: (store: File, rig: SecureTestRig) -> Unit)

    private fun shapes(): List<Shape> {
        val userFile = "user-data.txt"
        return listOf(
            Shape(
                "a directory",
                make = { store: File, _ -> store.mkdirs(); File(store, userFile).writeText("mine") },
                check = { store: File, _ -> assertTrue(store.isDirectory && File(store, userFile).readText() == "mine", "the directory or its content changed") },
            ),
            Shape(
                "a link to a directory",
                make = { store: File, rig: SecureTestRig ->
                    val target = File(rig.directory, "elsewhere-dir").apply { mkdirs() }
                    File(target, userFile).writeText("mine")
                    Files.createSymbolicLink(store.toPath(), target.toPath())
                },
                check = { store: File, rig: SecureTestRig ->
                    assertTrue(Files.isSymbolicLink(store.toPath()), "the link was replaced")
                    assertEquals(listOf(userFile), File(rig.directory, "elsewhere-dir").list().orEmpty().toList(), "the linked directory changed")
                },
            ),
            Shape(
                "a link to a named pipe",
                make = { store: File, rig: SecureTestRig ->
                    val pipe = File(rig.directory, "elsewhere-pipe")
                    NamedPipes.createOrSkip(pipe)
                    Files.createSymbolicLink(store.toPath(), pipe.toPath())
                },
                check = { store: File, rig: SecureTestRig ->
                    assertTrue(Files.isSymbolicLink(store.toPath()), "the link was replaced")
                    assertTrue(File(rig.directory, "elsewhere-pipe").exists() && !File(rig.directory, "elsewhere-pipe").isFile, "the pipe was replaced")
                },
            ),
            Shape(
                "a link to /dev/null",
                make = { store: File, _ -> Files.createSymbolicLink(store.toPath(), Path.of("/dev/null")) },
                check = { store: File, _ -> assertEquals(Path.of("/dev/null"), Files.readSymbolicLink(store.toPath()), "the link was replaced") },
            ),
        )
    }

    /**
     * Every operation of the OS-key store and of the app-key store (with `changeKey`) on a store path of each shape: a typed
     * answer within the bound (a pipe would block), `OperationFailed` for everything that has to read the file, and the
     * shape untouched: no quarantine (a quarantine of a link or a directory would move the user's thing), no file created
     * through the link, no key item, no temporary file. Red on: a hang, a thrown exception, a moved or replaced shape, an
     * item created for a store that cannot hold a file.
     */
    @Test
    fun `a store path that is not a regular file fails every operation of both store types and is never moved or replaced`() {
        TestHost.assumePosixFileSemantics("symbolic links, pipes and /dev/null")
        for (shape: Shape in shapes()) {
            for (kind: String in listOf("os", "app")) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val path: File = if (kind == "os") rig.dataFile() else File(rig.directory, "${appKeyStoreId("app")}.bin")
                    shape.make(path, rig)
                    val namesBefore: List<String> = rig.fileNames()
                    val pipes: List<File> = listOf(File(rig.directory, "elsewhere-pipe"))
                    val calls: List<Pair<String, suspend () -> StorageResult<*>>> = if (kind == "os") {
                        val os = rig.store()
                        listOf(
                            "os get" to { os.get("a") }, "os put" to { os.put("a", "1") }, "os remove" to { os.remove("a") },
                            "os clear" to { os.clear() }, "os ensureKey" to { os.ensureKey() },
                        )
                    } else {
                        val app = JvmAppKeyEncryptedKeyValueStorage("app", rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 9 } }, Dispatchers.IO, CrossProcessLock())
                        listOf(
                            "app get" to { app.get("a") }, "app put" to { app.put("a", "1") }, "app remove" to { app.remove("a") },
                            "app clear" to { app.clear() }, "app ensureKey" to { app.ensureKey() },
                            "app changeKey" to { app.changeKey(AppEncryptionKey.Raw { ByteArray(32) { 8 } }) },
                        )
                    }
                    for ((label: String, call: suspend () -> StorageResult<*>) in calls) {
                        val result: StorageResult<*> = NamedPipes.answersWithinBound("${shape.label}: $label", pipes, call)
                        assertIs<StorageError.OperationFailed>(failure(result), "${shape.label}: $label")
                    }
                    shape.check(path, rig)
                    assertEquals(emptyList(), rig.quarantined(), "${shape.label} ($kind): something was moved to quarantine")
                    assertEquals(emptyMap(), rig.fake.macItems.toMap(), "${shape.label} ($kind): a keychain item was created for a store that cannot hold a file")
                    // The lock file of the resolved path may appear (the lock is taken before the file is read); nothing else may.
                    val created: List<String> = rig.fileNames().filter { it !in namesBefore && !it.endsWith(".lock") }
                    assertEquals(emptyList(), created, "${shape.label} ($kind): a file was created or left")
                }
            }
        }
    }

    /**
     * A store path that is a symbolic link whose target does not exist: reading finds nothing (a `get` of nothing is
     * `Success(null)`), but a write can neither create the name (it is taken by the link) nor may it create the target
     * through the link (somewhere the app never chose). The call must answer at once with a typed failure that is not
     * "try again later": 05 answers the same structure at the lock path with `OperationFailed` "at once", and D-P11 (round-7
     * decision proposal) names "a store file ... a symbolic link whose target does not exist" as `OperationFailed`. Red on:
     * the target created, the link replaced, a thrown exception, or `Unavailable` after futile create rounds (each round
     * makes a data key, and on Linux without UI or on Windows a native call: counted below).
     */
    @Test
    fun `a dangling link at the store path fails a write with OperationFailed at once and never creates the target`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        val wrong: MutableList<String> = mutableListOf()
        for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
            SecureTestRig(os).use { rig: SecureTestRig ->
                val target = File(rig.directory, "moved-away.bin")
                Files.createSymbolicLink(rig.dataFile().toPath(), target.toPath())
                val store = rig.store(options = DesktopSecureStorageOptions(allowUserInteraction = false))
                runBlocking {
                    withTimeout(HANG_BOUND) {
                        assertEquals(StorageResult.Success(null), store.get("a"), "$os: a get of a store with no file")
                        for ((label: String, call: suspend () -> StorageResult<Unit>) in listOf("put" to suspend { store.put("a", "1") }, "ensureKey" to suspend { store.ensureKey() })) {
                            val calls: Int = rig.fake.calls.size
                            val outcome: Result<StorageResult<Unit>> = runCatching { call() }
                            val nativeCalls: List<String> = rig.fake.callNames.drop(calls)
                            assertTrue(outcome.isSuccess, "$os $label: an exception escaped: ${outcome.exceptionOrNull()}")
                            assertFalse(target.exists(), "$os $label: the target of the link was created")
                            assertEquals(target.toPath(), Files.readSymbolicLink(rig.dataFile().toPath()), "$os $label: the link was replaced")
                            assertEquals(emptyMap(), rig.fake.macItems.toMap(), "$os $label: an item was created")
                            assertEquals(emptyMap(), rig.fake.secretItems.toMap(), "$os $label: an item was created")
                            assertEquals(listOf(rig.dataFile().name), rig.fileNames().filter { !it.endsWith(".lock") }, "$os $label: a file (a temporary one included) was created or left")
                            val error: StorageError = failure(outcome.getOrThrow())
                            if (error !is StorageError.OperationFailed) wrong += "$os $label: $error after native calls $nativeCalls"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList(), wrong, "a dangling link at the store path must be OperationFailed at once, not a retry-later answer after futile create rounds")
    }

    // ---- 05: "A call on an interrupted thread" ----

    /**
     * 05 (round 7): "When the store's dispatcher runs a call on a thread whose interrupt status is set (for example
     * `Dispatchers.Unconfined` called from an interrupted thread), the JDK refuses the file I/O: the call answers
     * `OperationFailed` ..., writes nothing, leaves no temporary file or lock, keeps the status, and can be repeated once the
     * status is cleared." Checked for every operation of both store types and `changeKey`, on a store WITH data: the answer,
     * the status, the file byte for byte, no temporary file, the lock released (the repeat would time out otherwise: the
     * lock deadline is the 10 s budget, the hang bound is above it), and the repeat. The cause is pinned as observed (the
     * exact shape is a docs point of the report). Red on: any claim of the paragraph that does not hold.
     */
    @Test
    fun `every operation on an interrupted thread is refused keeps the status changes nothing and repeats once cleared`() {
        SecureTestRig(DesktopOs.MACOS, dispatcher = Dispatchers.Unconfined).use { rig: SecureTestRig ->
            val appFile = File(rig.directory, "${appKeyStoreId("app")}.bin")
            val oldKey = AppEncryptionKey.Raw { ByteArray(32) { 5 } }
            val newKey = AppEncryptionKey.Raw { ByteArray(32) { 6 } }
            val causes: MutableList<String> = mutableListOf()
            runBlocking {
                withTimeout(HANG_BOUND) {
                    assertEquals(StorageResult.Success(Unit), rig.store().put("a", "1"), "control")
                    var app = JvmAppKeyEncryptedKeyValueStorage("app", rig.directory, oldKey, Dispatchers.Unconfined, CrossProcessLock())
                    assertEquals(StorageResult.Success(Unit), app.put("a", "1"), "control")
                    val os = rig.store()
                    val calls: List<Triple<String, File, suspend () -> StorageResult<*>>> = listOf(
                        Triple("os get", rig.dataFile()) { os.get("a") }, Triple("os put", rig.dataFile()) { os.put("b", "2") },
                        Triple("os remove", rig.dataFile()) { os.remove("a") }, Triple("os clear", rig.dataFile()) { os.clear() },
                        Triple("os ensureKey", rig.dataFile()) { os.ensureKey() },
                        Triple("app get", appFile) { app.get("a") }, Triple("app put", appFile) { app.put("b", "2") },
                        Triple("app remove", appFile) { app.remove("a") }, Triple("app clear", appFile) { app.clear() },
                        Triple("app ensureKey", appFile) { app.ensureKey() }, Triple("app changeKey", appFile) { app.changeKey(newKey) },
                    )
                    for ((label: String, file: File, call: suspend () -> StorageResult<*>) in calls) {
                        val before: ByteArray = file.readBytes()
                        val names: List<String> = rig.fileNames()
                        Thread.currentThread().interrupt()
                        val result: StorageResult<*> = call()
                        val kept: Boolean = Thread.interrupted()
                        assertTrue(kept, "$label: the interrupt status was not kept")
                        val failed = assertIs<StorageError.OperationFailed>(failure(result), "$label: refused")
                        val chain: List<Throwable> = generateSequence(failed.cause) { it.cause }.toList()
                        assertTrue(chain.any { it is ClosedByInterruptException }, "$label: cause chain ${chain.map { it::class.java.simpleName }}")
                        causes += "$label=${chain.map { it::class.java.simpleName }}"
                        assertContentEquals(before, file.readBytes(), "$label: the file changed")
                        assertEquals(names, rig.fileNames(), "$label: a file was created or left (temporary or other)")
                        assertTrue(call() is StorageResult.Success, "$label: the call does not repeat once the status is cleared")
                        if (label == "app changeKey") {
                            // The repeat re-keyed the store: rebind the next instances (none follow, kept for clarity).
                            app = JvmAppKeyEncryptedKeyValueStorage("app", rig.directory, newKey, Dispatchers.Unconfined, CrossProcessLock())
                            assertEquals(StorageResult.Success(null), app.get("a"), "the repeated changeKey re-keyed the store")
                        }
                    }
                }
            }
            // 05: "cause: an IOException that wraps ClosedByInterruptException", for every operation (checked, green).
            assertTrue(causes.all { it.endsWith("=[IOException, ClosedByInterruptException]") }, "observed causes: $causes")
        }
    }

    // ---- the undelivered-result wipe and the key cache ----

    /** Delegates to [fake]; the macOS copy call blocks on [release] once it has fetched, and keeps the arrays it hands out. */
    private class BlockingCopyBinding(private val fake: FakeNativeKeyStoreBinding) : NativeKeyStoreBinding {
        val handedOut: CopyOnWriteArrayList<ByteArray> = CopyOnWriteArrayList()
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CountDownLatch = CountDownLatch(1)

        override fun macOs(): MacKeychainCalls = object : MacKeychainCalls by fake.macOs() {
            override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
                val raw: RawResult<ByteArray?> = fake.macOs().copyGenericPassword(service, account, allowUi)
                if (raw is RawResult.Success) raw.value?.let { handedOut += it }
                if (handedOut.size == 1 && !entered.isCompleted) {
                    entered.complete(Unit)
                    check(release.await(HANG_BOUND.inWholeMilliseconds, TimeUnit.MILLISECONDS)) { "the test never released the fetch" }
                }
                return raw
            }
        }

        override fun windows(): DpapiCalls = fake.windows()

        override fun linux(): SecretServiceCalls = fake.linux()
    }

    /**
     * A cold `get` whose caller is cancelled while the native fetch runs (the key exists and is returned after the caller
     * left). The fetched key must reach the wipe handler, never the key cache (a key no caller asked for any more must not
     * stay in process memory), the file is untouched, and the next `get` fetches again and is served. Control: the binding
     * handed out a 32-byte key that was not zero. Red on: the key array left unwiped, a cache entry made from it, or the
     * next call not served.
     */
    @Test
    fun `a key fetched for a caller that left is wiped and never cached`() {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            runBlocking {
                withTimeout(HANG_BOUND) {
                    assertEquals(StorageResult.Success(Unit), rig.store().put("a", "1"), "control")
                    val before: ByteArray = rig.dataFile().readBytes()
                    val binding = BlockingCopyBinding(rig.fake)
                    val cache = OsKeyCache()
                    val store = rig.store(cache = cache, source = FixedBindingSource(BindingSelection.Selected(binding, DesktopOs.MACOS, BindingKind.FAKE)))
                    val job = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { store.get("a") }
                    binding.entered.await()
                    assertTrue(binding.handedOut.single().any { it != 0.toByte() }, "control: the binding handed out a real key")
                    job.cancelAndJoin()
                    assertEquals(0, cache.size, "a key was cached for a caller that left")
                    binding.release.countDown()
                    // The executor has one thread: this call runs after the abandoned one has delivered or discarded its value.
                    assertEquals(StorageResult.Success("1"), store.get("a"), "the next get is served")
                    assertEquals(2, binding.handedOut.size, "control: the next get fetched again")
                    assertTrue(binding.handedOut.first().all { it == 0.toByte() }, "the key fetched for the caller that left was never wiped")
                    assertContentEquals(before, rig.dataFile().readBytes(), "the file changed")
                }
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
