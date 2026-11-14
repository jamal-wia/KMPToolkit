@file:Suppress("UNUSED_VARIABLE", "unused")

package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.AppDirectories
import io.github.jamal_wia.kmptoolkit.files.AppRoot
import io.github.jamal_wia.kmptoolkit.files.Backup
import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.KeyedMutex
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.PathKey
import io.github.jamal_wia.kmptoolkit.files.PathLocks
import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.files.SqliteFiles
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.errorOrNull
import io.github.jamal_wia.kmptoolkit.files.exists
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import io.github.jamal_wia.kmptoolkit.files.withIo
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// path-hygiene-file: logical-paths the samples are the documentation's own and use its path spellings

/*
 * Round 5 of the breaker for unit G, Q4 (docs newcomer). Every code sample of docs/kmptoolkit-files 01-overview,
 * 02-getting-started and 03-guide (and the executable ones of 06-testing) copied as the docs print them, compiled and run
 * against the fake and against the real engine. A sample that does not compile, or whose documented outcome does not hold,
 * is a finding. The comment "DOC" marks code taken verbatim; the only additions are the declarations a reader would
 * have in their own app (askToFreeSpace, report, LIMIT, the paths).
 */

// ---- 01-overview -------------------------------------------------------------------------------------------

internal var reported: FileError? = null
internal var askedToFree: Boolean = false
internal fun askToFreeSpace() { askedToFree = true }
internal fun report(error: FileError) { reported = error }

// DOC 01-overview
suspend fun saveDraft(files: FileAccess, path: String, bytes: ByteArray): FileResult<Unit> =
    files.withIo { writeAtomically(path, bytes, WriteOptions(Durability.Synced, createParentDirectories = true)) }

internal suspend fun overviewSample(files: FileAccess, path: String, bytes: ByteArray) {
    // DOC 01-overview
    when (val result = saveDraft(files, path, bytes)) {
        is FileResult.Success -> Unit
        is FileResult.Failure -> when (result.error) {
            is FileError.NoSpace -> askToFreeSpace()
            is FileError.AccessDenied, is FileError.NotFound -> report(result.error)
            else -> report(result.error)
        }
    }
}

// ---- 02-getting-started ------------------------------------------------------------------------------------

// DOC 02-getting-started section 3
class ProfileStore(private val files: FileAccess, private val path: String) {

    suspend fun save(json: String): FileResult<Unit> = files.withIo {
        writeAtomically(
            path,
            json.encodeToByteArray(),
            WriteOptions(durability = Durability.Synced, createParentDirectories = true),
        )
    }

    suspend fun load(): String? =
        files.withIo { readBytes(path, maxBytes = 1L shl 20).getOrNull()?.decodeToString() }
}

// DOC 02-getting-started section 3 (composition root)
fun profileStore(files: FileAccess, directories: AppDirectories): ProfileStore? =
    when (val dir = directories.directory(AppRoot.Files, "profile")) {
        is FileResult.Success -> ProfileStore(files, Paths.join(dir.value, "profile.json"))
        is FileResult.Failure -> null
    }

/** What section 4 says a test writes in a few lines: returns paths of the fake and creates the directory with createDirectories. */
internal class SampleDirectories(private val files: FileAccess, private val base: String, private val join: (String, String) -> String) : AppDirectories {
    override fun rootPath(root: AppRoot): String = join(base, root.name.lowercase())

    override fun directory(root: AppRoot, name: String, backup: Backup): FileResult<String> {
        FileNames.requirePlainName(name)
        val path: String = join(rootPath(root), name)
        return files.createDirectories(path).let { if (it.isSuccess) FileResult.Success(path) else FileResult.Failure(it.errorOrNull()!!) }
    }

    override fun excludeFromBackup(path: String): FileResult<Unit> = FileResult.Success(Unit)
}

// ---- 03-guide ----------------------------------------------------------------------------------------------

// DOC 03-guide "Calling it"
suspend fun reload(files: FileAccess, path: String): ByteArray? =
    files.withIo { readBytes(path, maxBytes = 4L shl 20).getOrNull() }

// DOC 03-guide "Read-modify-write with PathLocks"
suspend fun increment(files: FileAccess, path: String): FileResult<Unit> =
    PathLocks.withLock(path) {
        files.withIo {
            val current: Long = readBytes(path, 32).getOrNull()?.decodeToString()?.toLongOrNull() ?: 0L
            writeAtomically(path, (current + 1).toString().encodeToByteArray())
        }
    }

// DOC 03-guide "KeyedMutex"
private val locks: KeyedMutex<String> = KeyedMutex()

suspend fun upload(id: String) = locks.withLock(id) { /* one at a time per id */ }

// DOC 03-guide "Temporary-file leftovers and sweep"
suspend fun cleanUp(files: FileAccess, directory: String): Int =
    files.withIo { cancelled ->
        sweep(directory, olderThanMillis = 60 * 60 * 1000L, cancellation = cancelled) { name ->
            FileNames.isTempName(name)
        }.getOrNull() ?: 0
    }

internal const val LIMIT: Long = 10L

/** The five blocks of the guide, run in order against [files] under [root]; [join] builds a path of the host. */
internal suspend fun runGuideSamples(files: FileAccess, root: String, join: (String, String) -> String, aging: ((String) -> Unit)?) {
    // -- 01 overview
    reported = null
    val draft: String = join(join(root, "drafts"), "d.txt")
    overviewSample(files, draft, "draft".encodeToByteArray())
    assertEquals(null, reported, "the happy path reported an error")
    assertEquals("draft", reload(files, draft)?.decodeToString())
    // The failure branch: a missing directory is not created when the sample's options say it is, so use a NUL path: InvalidPath goes to report.
    overviewSample(files, "relative/path", ByteArray(1))
    assertTrue(reported is FileError.InvalidPath, "a relative path is InvalidPath: $reported")

    // -- 02 getting started, section 3
    val directories = SampleDirectories(files, join(root, "app"), join)
    val store: ProfileStore = assertNotNull(profileStore(files, directories), "profileStore returned null")
    assertTrue(store.save("""{"name":"Ada"}""").isSuccess)
    assertEquals("""{"name":"Ada"}""", store.load())
    assertTrue(store.save("""{"name":"Grace"}""").isSuccess)
    assertEquals("""{"name":"Grace"}""", store.load())
    assertNull(ProfileStore(files, join(join(root, "absent"), "p.json")).load(), "a store that never saved loads null")

    // -- 03 guide: durability sample
    val receiptPath: String = join(join(root, "receipts"), "r1.bin")
    val receiptBytes: ByteArray = ByteArray(1000) { it.toByte() }
    // DOC 03-guide "Durable small records"
    files.withIo {
        writeAtomically(
            path = receiptPath,
            bytes = receiptBytes,
            options = WriteOptions(
                durability = Durability.SyncedWithDirectory,
                createParentDirectories = true,
                protection = FileProtection.CompleteUntilFirstUserAuthentication, // iOS only
            ),
        )
    }.also { assertTrue(it.isSuccess, "$it") }
    assertTrue(receiptBytes.contentEquals(reload(files, receiptPath)))
    // WriteOptions also has: existing = Fail, tempDirectory, tempPrefix
    val failed = files.writeAtomically(receiptPath, ByteArray(1), WriteOptions(existing = ExistingFile.Fail))
    assertEquals(FileError.AlreadyExists(receiptPath), failed.errorOrNull(), "existing = Fail: AlreadyExists if anything is at the path")
    val staging: String = join(root, "staging")
    assertTrue(files.createDirectories(staging).isSuccess)
    val viaTemp = files.writeAtomically(receiptPath, ByteArray(2), WriteOptions(tempDirectory = staging, tempPrefix = ".myapp-"))
    assertTrue(viaTemp.isSuccess, "$viaTemp")
    assertEquals(emptyList(), files.list(staging).getOrNull(), "a temp directory is left empty")
    assertEquals(2, reload(files, receiptPath)?.size)

    // -- reading: the limits the guide states
    assertEquals(FileError.TooLarge(receiptPath, 2, 1), files.readBytes(receiptPath, 1).errorOrNull(), "a file larger than maxBytes is TooLarge")
    assertFailsWith<IllegalArgumentException> { files.readBytes(receiptPath, -1) }
    assertFailsWith<IllegalArgumentException> { files.readBytes(receiptPath, FileAccess.MAX_READ_BYTES + 1) }
    assertTrue(files.readBytes(receiptPath, 2).isSuccess)
    assertTrue(files.readBytes(receiptPath, 0).errorOrNull() is FileError.TooLarge)

    // -- PathLocks sample: 20 coroutines, no lost update, also under spellings that differ in case
    val counter: String = join(join(root, "counters"), "c")
    assertTrue(files.createDirectories(join(root, "counters")).isSuccess)
    coroutineScope { (1..20).map { async { increment(files, counter) } }.awaitAll().forEach { assertTrue(it.isSuccess, "$it") } }
    assertEquals("20", reload(files, counter)?.decodeToString(), "the PathLocks sample lost an update")
    assertEquals(PathKey.of(counter, CaseFolding.Always), PathKey.of(counter.uppercase(), CaseFolding.Always))
    var ran = false
    assertTrue(PathLocks.tryWithLock(counter) { ran = true }, "an idle path lock is taken by tryWithLock")
    assertTrue(ran)
    // -- KeyedMutex sample
    upload("a"); upload("b")
    val mutex: KeyedMutex<String> = KeyedMutex()
    mutex.withLock("k") { assertEquals(1, mutex.activeKeyCount) }
    assertEquals(0, mutex.activeKeyCount, "activeKeyCount is 0 when idle")
    mutex.withLock("k") { assertTrue(!mutex.tryWithLock("k") { }, "tryWithLock of a held key does not run") }

    // -- sweep sample
    val litter: String = join(root, "litter")
    assertTrue(files.createDirectories(litter).isSuccess)
    val temp: String = join(litter, ".kmptoolkit-123.tmp")
    val other: String = join(litter, "keep.txt")
    assertTrue(files.writeAtomically(temp, ByteArray(1)).isSuccess)
    assertTrue(files.writeAtomically(other, ByteArray(1)).isSuccess)
    assertEquals(0, cleanUp(files, litter), "a fresh leftover is older than nothing: kept")
    if (aging != null) {
        aging(temp)
        aging(other)
        assertEquals(1, cleanUp(files, litter), "an old temporary name is removed and other names are kept")
        assertTrue(files.exists(other) && !files.exists(temp))
    }
    assertEquals(0, cleanUp(files, join(root, "no-such-directory")), "an absent directory sweeps 0")
    assertFailsWith<IllegalArgumentException> { files.sweep(litter, -1) { true } }

    // -- recursive delete and tree size sample
    val cacheDir: String = join(root, "cache")
    assertTrue(files.writeAtomically(join(join(cacheDir, "a"), "f"), ByteArray(7), WriteOptions(createParentDirectories = true)).isSuccess)
    assertTrue(files.writeAtomically(join(cacheDir, "g"), ByteArray(5)).isSuccess)
    // DOC 03-guide "Recursive delete and tree size"
    files.withIo { cancelled ->
        val size: Long = treeSize(cacheDir, cancelled).getOrNull() ?: 0L
        if (size > LIMIT) deleteRecursively(cacheDir, cancelled)
    }
    assertEquals(null, files.metadata(cacheDir), "size 12 over the limit 10: the tree is removed")
    assertEquals(FileResult.Success(0L), files.treeSize(cacheDir), "an absent path is 0")
    assertEquals(FileError.Cancelled, files.deleteRecursively(root, CancellationCheck { true }).errorOrNull(), "a cancelled walk stops with Cancelled")
    assertEquals(FileResult.Success(Unit), files.delete(join(root, "no-such-entry")), "delete of an absent path is Success")
    val nonEmpty: String = join(root, "counters")
    assertEquals(FileError.DirectoryNotEmpty(nonEmpty), files.delete(nonEmpty).errorOrNull(), "a non-empty directory is DirectoryNotEmpty")

    // -- moving
    val from: String = join(root, "m-from")
    val to: String = join(root, "m-to")
    assertTrue(files.writeAtomically(from, "moved".encodeToByteArray()).isSuccess)
    assertTrue(files.writeAtomically(to, "target".encodeToByteArray()).isSuccess)
    assertEquals(FileError.AlreadyExists(to), files.move(from, to, MoveOptions(existing = ExistingFile.Fail)).errorOrNull(), "Fail never replaces anything")
    assertTrue(files.move(from, to).isSuccess)
    assertEquals("moved", reload(files, to)?.decodeToString())
    assertEquals(null, files.metadata(from))
    assertEquals(FileError.IsADirectory(join(root, "counters")), files.move(to, join(root, "counters")).errorOrNull(), "a directory at to is IsADirectory")
    val acrossOff = files.move(to, join(root, "m-to2"), MoveOptions(acrossVolumes = false))
    assertTrue(acrossOff.isSuccess, "a same-volume move does not need acrossVolumes: $acrossOff")

    // -- app directories and backup
    // DOC 03-guide "App directories and backup"
    val cache: String = (directories.directory(AppRoot.Cache, "thumbnails") as FileResult.Success).value
    val journal: String = (directories.directory(AppRoot.NoBackup, "journal") as FileResult.Success).value
    val exports: FileResult<String> = directories.directory(AppRoot.Files, "exports", Backup.Excluded)
    assertTrue(exports.isSuccess && files.metadata(cache)?.kind == FileKind.Directory && files.metadata(journal)?.kind == FileKind.Directory)
    assertFailsWith<IllegalArgumentException> { directories.directory(AppRoot.Files, "a/b") }
    assertFailsWith<IllegalArgumentException> { directories.directory(AppRoot.Files, "..") }

    // -- names and paths
    assertTrue(FileNames.isPlainName("a.txt") && !FileNames.isPlainName("a/b") && !FileNames.isPlainName("..") && !FileNames.isPlainName("a\u0000b"))
    assertTrue(FileNames.isPlainName("x".repeat(255)) && !FileNames.isPlainName("x".repeat(256)))
    assertTrue(FileNames.isWindowsReserved("CON .txt") && FileNames.isWindowsReserved("nul"))
    assertFailsWith<IllegalArgumentException> { FileNames.requirePlainName("a/b") }
    assertTrue(files.metadata("relative") == null && !files.exists("relative") && files.metadata("") == null)
    for (bad in listOf("relative", "", "/a\u0000b")) {
        assertEquals(FileError.InvalidPath(bad), files.readBytes(bad, 1).errorOrNull(), "a relative or empty or NUL path is InvalidPath")
    }
    assertEquals(listOf("/d/x.db-wal", "/d/x.db-shm", "/d/x.db-journal"), SqliteFiles.sidecarsOf("/d/x.db"))
    assertEquals("/a/b/c", Paths.join("/a/b", "c"))
    assertEquals("/a/c", Paths.normalizeLexically("/a/b/../c"))
    assertEquals(null, Paths.resolveInside("/base", "../x"))
    assertEquals("/base/x/y", Paths.resolveInside("/base", "x/./y"))
    // trailing separators: d/ and d name the same entry
    val dirWithSlash: String = join(root, "counters") + "/"
    assertEquals(FileKind.Directory, files.metadata(dirWithSlash)?.kind)

    // -- errors table
    val file: String = join(root, "m-to2")
    assertTrue(files.writeAtomically(join(file, "x"), ByteArray(1)).errorOrNull() is FileError.NotADirectory, "a file used as a directory is NotADirectory")
    assertTrue(files.writeAtomically(join(join(root, "missing"), "f"), ByteArray(1)).errorOrNull() is FileError.NotFound, "a missing parent is NotFound")
    assertTrue(files.writeAtomically(join(root, "counters"), ByteArray(1)).errorOrNull() is FileError.IsADirectory)
    assertTrue(files.writeAtomically(join(root, "n".repeat(256)), ByteArray(1)).errorOrNull() is FileError.NameTooLong)
    val cancelledWrite = files.deleteRecursively(join(root, "counters"), CancellationCheck { true })
    assertEquals(FileResult.Failure(FileError.Cancelled), cancelledWrite)
    val ok: FileResult<Int> = FileResult.Success(1)
    assertEquals(1, ok.getOrNull()); assertNull(ok.errorOrNull()); assertTrue(ok.isSuccess)
    assertFailsWith<IllegalArgumentException> { files.writeAtomically(join(root, "pfx"), ByteArray(1), WriteOptions(tempPrefix = "a/b")) }
    assertFailsWith<IllegalArgumentException> { WriteOptions(tempPrefix = "") }
}
