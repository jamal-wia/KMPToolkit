package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 9 (breaker finding B6-B1): the identity of an OS-key store is the real path of its file, and the service
 * is the stored name WITHOUT its `.bin` suffix. A store file that resolves (through a link the user made) to a name without the
 * suffix has no identity: stripping the suffix only "when present" gave `X` and `X.bin` one service, so a create of one store
 * deleted the other's key item. Now such a name is refused with `OperationFailed` before any key material, and nothing moves.
 *
 * Round 10 corrected one pin of this class (breaker B7-M2): the suffix is also accepted in another ASCII case when the store's own
 * configured name is not a link (a volume that folds case stores the configured `x.bin` as `x.BIN`, one file); behind a link only
 * the exact suffix is accepted, as above.
 *
 * The breaker's own test (`DesktopSecureStoreBreakerR7B6Test`) covers the collision itself (a create over a user file). Here: the
 * identity function, and the other half of the rule: a VALID store file that was renamed to a name without the suffix and linked
 * back answers every operation `OperationFailed` and is left byte-identical, then works again once the name is right.
 */
class StoreIdentitySuffixRound9Test {

    @Test
    fun `a stored name without the suffix has no identity and a name with it has the same as before`() {
        val withSuffix: StoreIdentity = StoreIdentity.of(RealStorePath("/x", "b.kmptoolkit.securestorage.bin"), normalizeUnicode = false)
        assertEquals("b.kmptoolkit.securestorage", withSuffix.service, "control: the service is the name without the suffix")
        for (name: String in listOf("b.kmptoolkit.securestorage", ".bin", "", "b.kmptoolkit.securestorage.bin.old")) {
            for (configuredIsLink: Boolean in listOf(true, false)) {
                val refused: IOException = assertFailsWith<IOException>("a store file named '$name' has no identity (link at the configured name: $configuredIsLink)") {
                    StoreIdentity.of(RealStorePath("/x", name), normalizeUnicode = false, configuredNameIsLink = { configuredIsLink })
                }
                assertEquals(false, refused.message.orEmpty().contains("securestorage"), "the message carries no name (D-26): ${refused.message}")
            }
        }
        assertNotEquals(
            withSuffix.cacheKey,
            StoreIdentity.of(RealStorePath("/x", "b.kmptoolkit.securestorage.bin.bin"), normalizeUnicode = false).cacheKey,
            "control: a doubled suffix is a different service, not a collision",
        )
    }

    /**
     * Round 10 (breaker B7-M2): the suffix may differ from the configured `.bin` only in ASCII case, and only when the store's own
     * configured name is not a link. (The round-9 version of this test asserted that `.BIN` is refused whatever the link: that was
     * wrong, because on a volume that folds case the configured `x.bin` and a stored `x.BIN` are one file and the store would be
     * locked out for good.)
     */
    @Test
    fun `the suffix in another ASCII case is the suffix only when the configured name is not a link`() {
        val stem = "b.kmptoolkit.securestorage"
        val expected: String = StoreIdentity.of(RealStorePath("/x", "$stem.bin"), normalizeUnicode = false).cacheKey
        for (spelling: String in listOf(".BIN", ".Bin", ".bIN", ".biN", ".BiN")) {
            val name = stem + spelling
            val refusedBehindLink: IOException = assertFailsWith<IOException>("$spelling behind a link at the configured name is refused") {
                StoreIdentity.of(RealStorePath("/x", name), normalizeUnicode = false, configuredNameIsLink = { true })
            }
            assertEquals(false, refusedBehindLink.message.orEmpty().contains("securestorage"), "the message carries no name (D-26)")
            assertEquals(
                expected,
                StoreIdentity.of(RealStorePath("/x", name), normalizeUnicode = false, configuredNameIsLink = { false }).cacheKey,
                "$spelling without a link is the same store as .bin: one service",
            )
        }
        // The link is asked only when the exact suffix is missing: a store file with the exact suffix never touches the file system for it.
        var asked = 0
        StoreIdentity.of(RealStorePath("/x", "$stem.bin"), normalizeUnicode = false, configuredNameIsLink = { asked++; true })
        assertEquals(0, asked, "the exact suffix is accepted without asking")
        // Only ASCII is folded: look-alikes are never the suffix, link or not.
        for (spelling: String in listOf(".b\u0131n", ".\uFF42\uFF49\uFF4E", ".bin ", ".bin\u0301", ".bi", "bin", ".bn")) {
            assertFailsWith<IOException>("'$spelling' is not the suffix even without a link") {
                StoreIdentity.of(RealStorePath("/x", stem + spelling), normalizeUnicode = false, configuredNameIsLink = { false })
            }
        }
        // The bare suffix in any case is no name at all.
        for (bare: String in listOf(".bin", ".BIN", ".Bin")) {
            assertFailsWith<IOException>("'$bare' alone is no store name") {
                StoreIdentity.of(RealStorePath("/x", bare), normalizeUnicode = false, configuredNameIsLink = { false })
            }
        }
    }

    @Test
    fun `a valid store file linked under a name ending in the suffix in another case is refused and left alone`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val store: EncryptedKeyValueStorage = rig.store()
                        assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the store exists")
                        val dataFile: File = rig.dataFile()
                        // A different base name, so that the link target never is the configured file on a volume that folds case.
                        val target = File(rig.directory, dataFile.name.removeSuffix(".bin") + "-elsewhere.BIN")
                        Files.move(dataFile.toPath(), target.toPath())
                        Files.createSymbolicLink(dataFile.toPath(), target.toPath())
                        val before: ByteArray = target.readBytes()
                        val itemsBefore: Int = rig.fake.macItems.size + rig.fake.secretItems.size
                        val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        for ((operation: StorageOperation, answer: StorageResult<*>) in listOf(
                            StorageOperation.GET to cold.get("k"),
                            StorageOperation.PUT to cold.put("k2", "v2"),
                            StorageOperation.ENSURE_KEY to cold.ensureKey(),
                        )) {
                            val error: StorageError = assertIs<StorageResult.Failure>(answer, "$os $operation: $answer").error
                            assertEquals(operation, assertIs<StorageError.OperationFailed>(error, "$os $operation: $error").operation, "$os: operation")
                        }
                        assertContentEquals(before, target.readBytes(), "$os: the target was changed")
                        assertEquals(itemsBefore, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: a key item was made or removed")
                        assertEquals(emptyList(), rig.fileNames().filter { ".undecryptable-" in it }, "$os: nothing was quarantined")
                    }
                }
            }
        }
    }

    @Test
    fun `a valid store file linked under a name without the suffix answers every operation OperationFailed and moves nothing`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val store: EncryptedKeyValueStorage = rig.store()
                        assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the store exists")
                        val dataFile: File = rig.dataFile()
                        val bare = File(rig.directory, dataFile.name.removeSuffix(".bin"))
                        Files.move(dataFile.toPath(), bare.toPath())
                        Files.createSymbolicLink(dataFile.toPath(), bare.toPath())
                        val before: ByteArray = bare.readBytes()
                        val namesBefore: List<String> = rig.fileNames().filterNot { it.endsWith(".lock") }
                        val itemsBefore: Int = rig.fake.macItems.size + rig.fake.secretItems.size
                        val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                        val answers: List<Pair<StorageOperation, StorageResult<*>>> = listOf(
                            StorageOperation.GET to store.get("k"),
                            StorageOperation.GET to cold.get("k"),
                            StorageOperation.PUT to store.put("k2", "v2"),
                            StorageOperation.REMOVE to store.remove("k"),
                            StorageOperation.ENSURE_KEY to store.ensureKey(),
                        )
                        for ((operation: StorageOperation, answer: StorageResult<*>) in answers) {
                            val error: StorageError = assertIs<StorageResult.Failure>(answer, "$os $operation: $answer").error
                            val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$os $operation: $error")
                            assertEquals(operation, failed.operation, "$os: the operation is named")
                            assertIs<IOException>(failed.cause, "$os $operation: the cause is the path fault")
                        }
                        assertContentEquals(before, bare.readBytes(), "$os: the file was changed")
                        assertEquals(namesBefore, rig.fileNames().filterNot { it.endsWith(".lock") }, "$os: a file was moved aside or made")
                        assertEquals(itemsBefore, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: a key item was made or removed")
                        Files.delete(dataFile.toPath())
                        Files.move(bare.toPath(), dataFile.toPath())
                        assertEquals(StorageResult.Success("v"), store.get("k"), "$os: the same instance reads once the name is right")
                        assertEquals(StorageResult.Success("v"), rig.store(cache = OsKeyCache()).get("k"), "$os: a cold instance reads the real key")
                    }
                }
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
