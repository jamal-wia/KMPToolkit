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
class ContractSuiteSurvivorsR1Test {

    private open class D(val r: FileAccess) : FileAccess by r

    private class Mutant(val name: String, val make: (FileAccess) -> FileAccess)

    private val mutants: List<Mutant> = listOf(
        Mutant("a symbolic link reports a nonzero size") { r -> object : D(r) { override fun metadata(path: String, followLinks: Boolean): FileMetadata? = r.metadata(path, followLinks)?.let { if (it.kind == FileKind.SymbolicLink) it.copy(sizeBytes = 9) else it } } },
        Mutant("readBytes does not follow links") { r -> object : D(r) { override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> = if (r.metadata(path, false)?.kind == FileKind.SymbolicLink) FileResult.Failure(FileError.Unsupported(path)) else r.readBytes(path, maxBytes) } },
        Mutant("no-replace write over a dangling link replaces it") { r -> object : D(r) { override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> = if (options.existing == ExistingFile.Fail && r.metadata(path, false)?.kind == FileKind.SymbolicLink && r.metadata(path, true) == null) r.writeAtomically(path, bytes, options.copy(existing = ExistingFile.Replace)) else r.writeAtomically(path, bytes, options) } },
        Mutant("move with Fail of a directory moves it") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = if (options.existing == ExistingFile.Fail && r.metadata(from, false)?.kind == FileKind.Directory && r.metadata(to, false) == null) r.move(from, to, options.copy(existing = ExistingFile.Replace), cancellation) else r.move(from, to, options, cancellation) } },
        Mutant("move with Fail of a symbolic link moves its target") { r -> object : D(r) { override fun move(from: String, to: String, options: MoveOptions, cancellation: CancellationCheck): FileResult<Unit> = if (options.existing == ExistingFile.Fail && r.metadata(from, false)?.kind == FileKind.SymbolicLink && r.metadata(to, false) == null) r.writeAtomically(to, ByteArray(1), WriteOptions(existing = ExistingFile.Fail)) else r.move(from, to, options, cancellation) } },
        Mutant("deleteRecursively of a link root removes the target contents") { r -> object : D(r) { override fun deleteRecursively(path: String, cancellation: CancellationCheck): FileResult<Unit> = if (r.metadata(path, false)?.kind == FileKind.SymbolicLink && r.metadata(path, true)?.kind == FileKind.Directory) { for (n: String in r.list(path).getOrNull().orEmpty()) r.deleteRecursively(Paths.join(path, n)); r.delete(path) } else r.deleteRecursively(path, cancellation) } },
    )
    @Test
    fun `the contract suite catches these six broken implementations`() {
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

}
