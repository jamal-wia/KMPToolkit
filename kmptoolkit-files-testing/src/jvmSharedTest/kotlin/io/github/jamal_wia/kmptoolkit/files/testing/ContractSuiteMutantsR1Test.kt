package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 1 of the breaker for unit G: the published FileAccessContract is the net under every decorator and custom
 * FileAccess, so each decorator below breaks exactly one normative sentence of FileAccess and the suite must notice.
 * A mutant that passes verifyAll is a sentence that no check would catch becoming false.
 */
class ContractSuiteMutantsR1Test {

    private open class D(val r: FileAccess) : FileAccess by r

    private class Mutant(val name: String, val make: (FileAccess) -> FileAccess)

    private val mutants: List<Mutant> = listOf(
        Mutant("metadata ignores followLinks=true") { r -> object : D(r) { override fun metadata(path: String, followLinks: Boolean): FileMetadata? = r.metadata(path, false) } },
        Mutant("metadata always follows links") { r -> object : D(r) { override fun metadata(path: String, followLinks: Boolean): FileMetadata? = r.metadata(path, true) } },
        Mutant("a directory reports a nonzero size") { r -> object : D(r) { override fun metadata(path: String, followLinks: Boolean): FileMetadata? = r.metadata(path, followLinks)?.let { if (it.kind == FileKind.Directory) it.copy(sizeBytes = 4096) else it } } },
        Mutant("list is reverse sorted") { r -> object : D(r) { override fun list(directory: String): FileResult<List<String>> = r.list(directory).let { if (it is FileResult.Success) FileResult.Success(it.value.reversed()) else it } } },
        Mutant("list sorts ignoring case") { r -> object : D(r) { override fun list(directory: String): FileResult<List<String>> = r.list(directory).let { if (it is FileResult.Success) FileResult.Success(it.value.sortedBy { n -> n.lowercase() }) else it } } },
        Mutant("list includes a dot entry") { r -> object : D(r) { override fun list(directory: String): FileResult<List<String>> = r.list(directory).let { if (it is FileResult.Success && it.value.isNotEmpty()) FileResult.Success(listOf(".") + it.value) else it } } },
        Mutant("list of an absent directory is NotFound") { r -> object : D(r) { override fun list(directory: String): FileResult<List<String>> = r.list(directory).let { if (it is FileResult.Success && it.value.isEmpty() && r.metadata(directory) == null) FileResult.Failure(FileError.NotFound(directory)) else it } } },
        Mutant("list of a file is an empty success") { r -> object : D(r) { override fun list(directory: String): FileResult<List<String>> = r.list(directory).let { if (it is FileResult.Failure && it.error is FileError.NotADirectory) FileResult.Success(emptyList()) else it } } },
        Mutant("createDirectories of an existing directory is AlreadyExists") { r -> object : D(r) { override fun createDirectories(path: String, durable: Boolean): FileResult<Unit> = if (r.metadata(path)?.kind == FileKind.Directory) FileResult.Failure(FileError.AlreadyExists(path)) else r.createDirectories(path, durable) } },
        Mutant("createDirectories over a file is AlreadyExists") { r -> object : D(r) { override fun createDirectories(path: String, durable: Boolean): FileResult<Unit> = r.createDirectories(path, durable).let { if (it is FileResult.Failure && it.error is FileError.NotADirectory) FileResult.Failure(FileError.AlreadyExists(path)) else it } } },
        Mutant("readBytes truncates instead of TooLarge") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = r.readBytes(path, maxBytes).let { if (it is FileResult.Failure && it.error is FileError.TooLarge) FileResult.Success(r.readBytes(path, FileAccess.MAX_READ_BYTES).getOrNull()!!.copyOf(maxBytes.toInt())) else it } } },
        Mutant("readBytes of a directory is NotFound") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = r.readBytes(path, maxBytes).let { if (it is FileResult.Failure && it.error is FileError.IsADirectory) FileResult.Failure(FileError.NotFound(path)) else it } } },
        Mutant("TooLarge reports the limit as the size") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = r.readBytes(path, maxBytes).let { if (it is FileResult.Failure && it.error is FileError.TooLarge) FileResult.Failure(FileError.TooLarge(path, maxBytes, maxBytes)) else it } } },
        Mutant("readBytes at exactly maxBytes is TooLarge") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = r.readBytes(path, maxBytes).let { if (it is FileResult.Success && maxBytes > 0 && it.value.size.toLong() == maxBytes) FileResult.Failure(FileError.TooLarge(path, maxBytes, maxBytes)) else it } } },
        Mutant("readBytes accepts a negative maxBytes") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = if (maxBytes < 0) FileResult.Failure(FileError.Io(path)) else r.readBytes(path, maxBytes) } },
        Mutant("no-replace write replaces") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options.copy(existing = ExistingFile.Replace)) } },
        Mutant("write leaves a temp file behind") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options).also { if (it is FileResult.Success) { val parent: String = options.tempDirectory ?: Paths.parentOf(path)!!; r.writeAtomically(Paths.join(parent, ".kmptoolkit-777.tmp"), ByteArray(0)) } } } },
        Mutant("write over a directory is Io") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options).let { if (it is FileResult.Failure && it.error is FileError.IsADirectory) FileResult.Failure(FileError.Io(path)) else it } } },
        Mutant("write creates a missing parent unasked") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options.copy(createParentDirectories = true)) } },
        Mutant("write ignores createParentDirectories") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options.copy(createParentDirectories = false)) } },
        Mutant("write through a link modifies the target") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = if (r.metadata(path, false)?.kind == FileKind.SymbolicLink && r.metadata(path, true) != null) { val target: FileMetadata? = r.metadata(path, true); r.writeAtomically(path + ".realtarget", bytes, options); FileResult.Success(Unit).also { check(target != null) } } else r.writeAtomically(path, bytes, options) } },
        Mutant("write ignores the temp directory and the prefix") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = r.writeAtomically(path, bytes, options.copy(tempDirectory = null)) } },
        Mutant("write of empty bytes is Io") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = if (bytes.isEmpty()) FileResult.Failure(FileError.Io(path)) else r.writeAtomically(path, bytes, options) } },
        Mutant("move with Fail replaces") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = r.move(from, to, options.copy(existing = ExistingFile.Replace), cancellation) } },
        Mutant("move ignores createParentDirectories") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = r.move(from, to, options.copy(createParentDirectories = false), cancellation) } },
        Mutant("move creates parents unasked") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = r.move(from, to, options.copy(createParentDirectories = true), cancellation) } },
        Mutant("move of a missing source is Io") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = r.move(from, to, options, cancellation).let { if (it is FileResult.Failure && it.error is FileError.NotFound) FileResult.Failure(FileError.Io(from)) else it } } },
        Mutant("move onto a directory is AlreadyExists") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = r.move(from, to, options, cancellation).let { if (it is FileResult.Failure && it.error is FileError.IsADirectory) FileResult.Failure(FileError.AlreadyExists(to)) else it } } },
        Mutant("move of a link copies the target as a file") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = if (r.metadata(from, false)?.kind == FileKind.SymbolicLink && r.metadata(from, true)?.kind == FileKind.RegularFile) { val bytes: ByteArray = r.readBytes(from, 1_000_000).getOrNull()!!; r.delete(from); r.writeAtomically(to, bytes) } else r.move(from, to, options, cancellation) } },
        Mutant("delete of a link removes the target too") { r -> object : D(r) { override fun delete(path: String): FileResult<Unit> { val target: String? = if (r.metadata(path, false)?.kind == FileKind.SymbolicLink) java.nio.file.Files.readSymbolicLink(java.io.File(path).toPath()).toString() else null; val removed: FileResult<Unit> = r.delete(path); if (target != null) r.delete(target); return removed } } },
        Mutant("delete of a non-empty directory removes it") { r -> object : D(r) { override fun delete(path: String): FileResult<Unit> = r.delete(path).let { if (it is FileResult.Failure && it.error is FileError.DirectoryNotEmpty) r.deleteRecursively(path) else it } } },
        Mutant("delete of an absent path is NotFound") { r -> object : D(r) { override fun delete(path: String): FileResult<Unit> = if (path.isNotEmpty() && Paths.isAbsolute(path) && path.indexOf('\u0000') < 0 && r.metadata(path, false) == null) FileResult.Failure(FileError.NotFound(path)) else r.delete(path) } },
        Mutant("delete of an empty directory is refused") { r -> object : D(r) { override fun delete(path: String): FileResult<Unit> = if (r.metadata(path, false)?.kind == FileKind.Directory) FileResult.Failure(FileError.DirectoryNotEmpty(path)) else r.delete(path) } },
        Mutant("deleteRecursively ignores cancellation") { r -> object : D(r) { override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> = r.deleteRecursively(path, CancellationCheck.Never) } },
        Mutant("deleteRecursively of an absent path is NotFound") { r -> object : D(r) { override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> = if (path.isNotEmpty() && Paths.isAbsolute(path) && path.indexOf('\u0000') < 0 && r.metadata(path, false) == null) FileResult.Failure(FileError.NotFound(path)) else r.deleteRecursively(path, cancellation) } },
        Mutant("deleteRecursively reports Success after cancelling") { r -> object : D(r) { override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> = r.deleteRecursively(path, cancellation).let { if (it is FileResult.Failure && it.error is FileError.Cancelled) FileResult.Success(Unit) else it } } },
        Mutant("treeSize counts a link as its target size") { r -> object : D(r) { override fun treeSize(path: String, cancellation: CancellationCheck): FileResult<Long> = r.treeSize(path, cancellation).let { if (it is FileResult.Success) { var extra: Long = 0; fun walk(d: String) { for (n: String in r.list(d).getOrNull().orEmpty()) { val c: String = Paths.join(d, n); val m: FileMetadata? = r.metadata(c, false); if (m?.kind == FileKind.SymbolicLink) extra += r.metadata(c, true)?.sizeBytes ?: 0 else if (m?.kind == FileKind.Directory) walk(c) } }; walk(path); FileResult.Success(it.value + extra) } else it } } },
        Mutant("treeSize of an absent path is NotFound") { r -> object : D(r) { override fun treeSize(path: String, cancellation: CancellationCheck): FileResult<Long> = if (path.isNotEmpty() && Paths.isAbsolute(path) && path.indexOf('\u0000') < 0 && r.metadata(path, false) == null) FileResult.Failure(FileError.NotFound(path)) else r.treeSize(path, cancellation) } },
        Mutant("treeSize ignores cancellation") { r -> object : D(r) { override fun treeSize(path: String, cancellation: CancellationCheck): FileResult<Long> = r.treeSize(path, CancellationCheck.Never) } },
        Mutant("treeSize skips empty files and counts directories as 1") { r -> object : D(r) { override fun treeSize(path: String, cancellation: CancellationCheck): FileResult<Long> = r.treeSize(path, cancellation).let { if (it is FileResult.Success && r.metadata(path, false)?.kind == FileKind.Directory) FileResult.Success(it.value + 1) else it } } },
        Mutant("sweep ignores the age") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = r.sweep(directory, 0, cancellation, match) } },
        Mutant("sweep recurses into directories") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> { val first: FileResult<Int> = r.sweep(directory, olderThanMillis, cancellation, match); if (first !is FileResult.Success) return first; var total: Int = first.value; for (n: String in r.list(directory).getOrNull().orEmpty()) { val c: String = Paths.join(directory, n); if (r.metadata(c, false)?.kind == FileKind.Directory) total += r.sweep(c, olderThanMillis, cancellation, match).getOrNull() ?: 0 }; return FileResult.Success(total) } } },
        Mutant("sweep deletes an empty directory that matches") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> { var extra: Int = 0; for (n: String in r.list(directory).getOrNull().orEmpty()) { val c: String = Paths.join(directory, n); if (match(n) && r.metadata(c, false)?.kind == FileKind.Directory && r.list(c).getOrNull().orEmpty().isEmpty()) { r.delete(c); extra++ } }; return r.sweep(directory, olderThanMillis, cancellation, match).let { if (it is FileResult.Success) FileResult.Success(it.value + extra) else it } } } },
        Mutant("sweep keeps an old symbolic link") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = r.sweep(directory, olderThanMillis, cancellation) { n: String -> match(n) && r.metadata(Paths.join(directory, n), false)?.kind != FileKind.SymbolicLink } } },
        Mutant("sweep swallows the matcher's exception") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = r.sweep(directory, olderThanMillis, cancellation) { n: String -> try { match(n) } catch (_: RuntimeException) { false } } } },
        Mutant("sweep ignores cancellation") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = r.sweep(directory, olderThanMillis, CancellationCheck.Never, match) } },
        Mutant("sweep of an absent directory is NotFound") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = r.sweep(directory, olderThanMillis, cancellation, match).let { if (it is FileResult.Success && r.metadata(directory) == null) FileResult.Failure(FileError.NotFound(directory)) else it } } },
        Mutant("sweep accepts a negative age") { r -> object : D(r) { override fun sweep(directory: String, olderThanMillis: Long, cancellation: CancellationCheck, match: (String) -> Boolean): FileResult<Int> = if (olderThanMillis < 0) FileResult.Success(0) else r.sweep(directory, olderThanMillis, cancellation, match) } },
        Mutant("freeSpace of a missing path is null") { r -> object : D(r) { override fun freeSpaceBytes(path: String): Long? = if (r.metadata(path) == null) null else r.freeSpaceBytes(path) } },
        Mutant("freeSpace of an invalid path is zero") { r -> object : D(r) { override fun freeSpaceBytes(path: String): Long? = r.freeSpaceBytes(path) ?: 0L } },
        Mutant("a relative path is NotFound not InvalidPath on delete") { r -> object : D(r) { override fun delete(path: String): FileResult<Unit> = if (!Paths.isAbsolute(path)) FileResult.Success(Unit) else r.delete(path) } },
        Mutant("a NUL path is accepted by readBytes as NotFound") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = if (path.indexOf('\u0000') >= 0) FileResult.Failure(FileError.NotFound(path)) else r.readBytes(path, maxBytes) } },
    )

    @Test
    fun `every mutant of a normative sentence is caught by the contract suite`() {
        val survivors: MutableList<String> = ArrayList()
        val invalid: MutableList<String> = ArrayList()
        for (mutant: Mutant in mutants) {
            try {
                FileAccessContract { realFixture(mutant.make(createFileAccess())) }.verifyAll()
                survivors += mutant.name
            } catch (e: FileAccessContractViolation) {
                println("R1-CAUGHT ${mutant.name} :: ${e.message?.take(160)}")
            } catch (e: Throwable) {
                // A mutant that blows up by itself proves nothing about the suite.
                invalid += mutant.name + " (" + e::class.simpleName + ": " + e.message?.take(80) + ")"
            }
        }
        assertTrue(invalid.isEmpty(), "mutants that failed by themselves, not through a contract violation: $invalid")
        assertTrue(survivors.isEmpty(), "the contract suite passes ${survivors.size} of ${mutants.size} broken implementations: $survivors")
    }

    @Test
    fun `the unbroken implementation passes the suite so a caught mutant is a real catch`() {
        FileAccessContract { realFixture(createFileAccess()) }.verifyAll()
        FileAccessContract { realFixture(object : D(createFileAccess()) {}) }.verifyAll()
    }
}
